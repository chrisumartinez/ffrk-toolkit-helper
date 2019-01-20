package com.ffrktoolkit.ffrktoolkithelper;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;

import com.ffrktoolkit.ffrktoolkithelper.parser.InventoryParser;
import com.ffrktoolkit.ffrktoolkithelper.util.DropUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.littleshoot.proxy.HttpFilters;
import org.littleshoot.proxy.HttpFiltersAdapter;
import org.littleshoot.proxy.HttpFiltersSourceAdapter;
import org.littleshoot.proxy.HttpProxyServer;
import org.littleshoot.proxy.impl.DefaultHttpProxyServer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.util.CharsetUtil;

public class ProxyService extends Service implements View.OnTouchListener, View.OnClickListener {
    private HttpProxyServer server;
    private String LOG_TAG = "FFRKToolkitHelper";
    private InventoryParser inventoryParser;
    private final static int PROXY_NOTIFICATION_ID = 176123744;

    public ProxyService() {
        Log.d(LOG_TAG, "Service created.");
        inventoryParser = new InventoryParser();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() == null) {
            return START_STICKY;
        }

        String action = intent.getAction();
        if (getString(R.string.intent_start_proxy).equals(action)) {
            if (server == null) {
                startProxy();
            }
        }
        else if (getString(R.string.intent_stop_proxy).equals(action)) {
            if (this.server != null) {
                Log.d(LOG_TAG, "Stopping proxy.");
                this.server.abort();

                NotificationManager notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                notificationManager.cancel(ProxyService.PROXY_NOTIFICATION_ID);

                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this.getApplicationContext());
                prefs.edit().putBoolean("enableProxy", false).commit();

                Intent broadcastIntent = new Intent("com.ffrktoolkit.ffrktoolkithelper");
                Bundle extras = new Bundle();
                extras.putString("action", intent.getAction());
                broadcastIntent.putExtras(extras);
                getApplicationContext().sendBroadcast(broadcastIntent);

                this.server = null;
                this.stopSelf();
            }
        }

        return START_STICKY;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(LOG_TAG, "onCreate");
        startProxy();
    }

    @Override
    public void onClick(View v) {

    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        return false;
    }

    @Override
    public IBinder onBind(Intent intent) {
        // TODO: Return the communication channel to the service.
        Log.d(LOG_TAG, "onBind");
        throw new UnsupportedOperationException("Not yet implemented");
    }

    public void startProxy() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        Log.d(LOG_TAG, String.valueOf(prefs.getBoolean("enableProxy", false)));
        if (!prefs.getBoolean("enableProxy", false)) {
            return;
        }

        createProxyNotification();
        Log.d(LOG_TAG, "startProxy");
        int port = PreferenceManager.getDefaultSharedPreferences(this).getInt("proxyPort", 8081);
        this.server = DefaultHttpProxyServer.bootstrap().withPort(port)
                .withFiltersSource(new HttpFiltersSourceAdapter() {
                    public HttpFilters filterRequest(HttpRequest originalRequest, ChannelHandlerContext ctx) {
                        return new HttpFiltersAdapter(originalRequest) {
                            @Override
                            public HttpResponse clientToProxyRequest(HttpObject httpObject) {
                                // TODO: implement your filtering here
                                if (isUrlForFfrk(originalRequest.getUri()) && (httpObject instanceof HttpRequest)) {
                                    Log.d(LOG_TAG, "Sending request to " + ((HttpRequest) httpObject).getUri());
                                }

                                return null;
                            }

                            @Override
                            public HttpObject serverToProxyResponse(HttpObject httpObject) {
                                Log.d(LOG_TAG, httpObject.getClass().getName());
                                if (isUrlForFfrk(originalRequest.getUri()) && httpObject instanceof FullHttpResponse) {
                                    FullHttpResponse response = (FullHttpResponse) httpObject;
                                    Log.d(LOG_TAG, "Received response for " + originalRequest.getUri());

                                    try {
                                        URL urlPath = new URL(originalRequest.getUri());
                                        Log.d(LOG_TAG, "Response path: " + urlPath.getPath());
                                        String responseContent = response.content().toString(CharsetUtil.UTF_8);
                                        parseFfrkResponse(originalRequest.getUri(), responseContent);
                                        Log.d(LOG_TAG, responseContent);
                                    }
                                    catch(Exception e) {
                                        Log.e(LOG_TAG, "Exception while parsing response content.", e);
                                    }
                                }

                                return httpObject;
                            }
                        };
                    }

                    public int getMaximumResponseBufferSizeInBytes()
                    {
                        return 10485760;
                    }
                })
                .start();
    }

    private void parseFfrkResponse(String requestUri, String response) {
        try {
            if (requestUri.contains("/dff/party/list")) {
                parsePartyData(requestUri, response);
                parseInventoryData("inventory", requestUri, response);
            }
            else if (requestUri.contains("/dff/warehouse/get_equipment_list")) {
                parseInventoryData("vault", requestUri, response);
            }
            else if (requestUri.contains("get_battle_init_data")) {
                parseBattleData(response);
            }
        }
        catch (Exception e) {
            Log.e(LOG_TAG, "Exception while parsing FFRK response." ,e);
        }
    }

    private void parsePartyData(String requestUri, String partyDataResponse) throws JSONException {
        JSONObject json = new JSONObject(partyDataResponse);
        JSONArray soulbreaks = json.getJSONArray("soul_strikes");
        JSONArray legendMateria = json.getJSONArray("legend_materias");

        JSONObject filteredJson = new JSONObject();
        filteredJson.put("soul_strikes", soulbreaks);
        filteredJson.put("legend_materias", legendMateria);


        // Check for an existing inventory
        String existingInventory;
        try {
            String fileName = isGlobalUrl(requestUri) ? getString(R.string.file_inventory_global_json) : getString(R.string.file_inventory_jp_json);
            File file = new File(getApplicationContext().getFilesDir(), fileName);
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());

            if (file.exists()) {
                FileInputStream inputStream = new FileInputStream(file);
                byte[] data = new byte[(int) file.length()];
                inputStream.read(data);
                existingInventory = new String(data, CharsetUtil.UTF_8);
                JSONObject existingInventoryJson = new JSONObject(existingInventory);

                boolean hasInventoryChanged = inventoryParser.hasInventoryChanged(json, existingInventoryJson, isGlobalUrl(requestUri) ? "global" : "japan");
                if (hasInventoryChanged) {
                    prefs.edit().putBoolean("hasInventoryChanged", true).commit();
                }
            }
            else {
                prefs.edit().putBoolean("hasInventoryChanged", true).commit();
            }
        }
        catch (Exception e) {
            Log.w(LOG_TAG, "Exception while parsing existing inventory, ignoring.", e);
        }

        FileOutputStream outputStream;
        try {
            String fileName = isGlobalUrl(requestUri) ? getString(R.string.file_inventory_global_json) : getString(R.string.file_inventory_jp_json);
            outputStream = openFileOutput(fileName, Context.MODE_PRIVATE);
            outputStream.write(filteredJson.toString().getBytes());
            outputStream.close();
        } catch (Exception e) {
            Log.e(LOG_TAG, "Exception while writing inventory json to storage.", e);
            return;
        }

        Log.d(LOG_TAG, "Inventory saved to file.");
    }

    private void parseInventoryData(String inventoryType, String requestUri, String inventoryResponse) throws JSONException {
        JSONObject json = new JSONObject(inventoryResponse);
        JSONArray equipments = json.getJSONArray("equipments");

        for (int i = 0, len = equipments.length(); i < len; i++) {
            JSONObject equipment = equipments.getJSONObject(i);
            if (equipment.has("evol_max_level_of_base_rarity")) {
                equipment.remove("evol_max_level_of_base_rarity");
            }

            if (equipment.has("hyper_evolve_recipe")) {
                equipment.remove("hyper_evolve_recipe");
            }
        }

        // Check for an existing inventory
        String existingInventory;
        String fileName = null;
        if ("inventory".equals(inventoryType)) {
            fileName = isGlobalUrl(requestUri) ? getString(R.string.file_equipment_global_json) : getString(R.string.file_equipment_jp_json);
        }
        else if ("vault".equals(inventoryType)) {
            fileName = isGlobalUrl(requestUri) ? getString(R.string.file_vault_global_json) : getString(R.string.file_vault_jp_json);
        }

        try {
            File file = new File(getApplicationContext().getFilesDir(), fileName);
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());

            if (file.exists()) {
                FileInputStream inputStream = new FileInputStream(file);
                byte[] data = new byte[(int) file.length()];
                inputStream.read(data);
                existingInventory = new String(data, CharsetUtil.UTF_8);
                JSONObject existingInventoryJson = new JSONObject(existingInventory);

                boolean hasInventoryChanged = inventoryParser.hasEquipmentChanged(json, existingInventoryJson, isGlobalUrl(requestUri) ? "global" : "japan");
                if (hasInventoryChanged) {
                    prefs.edit().putBoolean("hasInventoryChanged", true).commit();
                }
            }
            else {
                prefs.edit().putBoolean("hasInventoryChanged", true).commit();
            }
        }
        catch (Exception e) {
            Log.w(LOG_TAG, "Exception while parsing existing inventory, ignoring.", e);
        }

        FileOutputStream outputStream;
        try {
            outputStream = openFileOutput(fileName, Context.MODE_PRIVATE);
            outputStream.write(equipments.toString().getBytes());
            outputStream.close();
        } catch (Exception e) {
            Log.e(LOG_TAG, "Exception while writing inventory json to storage.", e);
            return;
        }

        Log.d(LOG_TAG, "Equipment inventory saved to file.");
    }

    private void parseBattleData(String response) {
        List<JSONObject> drops = new ArrayList<>();
        try {
            JSONObject responseData = new JSONObject(response);
            JSONObject battleData = responseData.getJSONObject("battle");
            JSONArray rounds = battleData.getJSONArray("rounds");

            for (int i = 0, roundsLen = rounds.length(); i < roundsLen; i++) {
                JSONObject round = rounds.getJSONObject(i);
                JSONArray enemies = round.getJSONArray("enemy");

                for (int j = 0, enemyLen = enemies.length(); j < enemyLen; j++) {
                    JSONObject enemy = enemies.getJSONObject(j);
                    JSONArray children = enemy.getJSONArray("children");

                    for (int k = 0, childrenLen = children.length(); k < childrenLen; k++) {
                        JSONObject child = children.getJSONObject(k);
                        JSONArray dropItemList = child.getJSONArray("drop_item_list");

                        for (int l = 0, dropsLen = dropItemList.length(); l < dropsLen; l++) {
                            JSONObject drop = dropItemList.getJSONObject(l);
                            drops.add(drop);
                        }
                    }
                }
            }

            Log.d(LOG_TAG, "Drop list size " + drops.size());
            ArrayList<String> dropTexts = getDropsString(drops);
            Intent dropsIntent = new Intent(this.getApplicationContext(), OverlayService.class);
            dropsIntent.putStringArrayListExtra("drops", dropTexts);
            getApplicationContext().startService(dropsIntent);
        }
        catch (Exception e) {
            Log.w(LOG_TAG, "Exception while parsing battle data.", e);
        }
    }

    private ArrayList<String> getDropsString(List<JSONObject> drops) throws JSONException {
        Log.d(LOG_TAG, "Starting drop parsing");
        Map<String, Integer> dropMap = new HashMap<>();
        for (JSONObject drop : drops) {
            String itemId = drop.optString("item_id");
            String rarity = drop.optString("rarity");
            int type = drop.optInt("type");
            Integer quantity = null;
            String mapKey = null;
            if (itemId != null && !"".equals(itemId.trim())) {
                Log.d(LOG_TAG, "Item ID: " + itemId);
                String dropName = DropUtils.getDropName(itemId);
                mapKey = rarity + "\u2605 " + dropName;
                quantity = dropMap.get(mapKey);
            }
            else {
                String stringType = String.valueOf(type);

                if (type == 11) {
                    stringType = "Gil";
                }

                mapKey = stringType;
                quantity = dropMap.get(mapKey);
            }

            if (quantity == null) {
                quantity = 0;
            }

            String quantityInDrop = drop.optString("num");
            if (quantityInDrop != null && !"".equals(quantityInDrop.trim())) {
                quantity += Integer.parseInt(quantityInDrop);
            }

            String amountInDrop = drop.optString("amount"); // used for Gil
            if (amountInDrop != null && !"".equals(amountInDrop.trim())) {
                quantity += Integer.parseInt(amountInDrop);
            }

            Log.d(LOG_TAG, "Parsed " + mapKey + " quan " + quantity);
            dropMap.put(mapKey, quantity);
        }

        ArrayList<String> dropsText = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : dropMap.entrySet()) {
            String dropName = entry.getKey();
            Integer quantity = entry.getValue();

            String dropText = null;
            if (dropName.equalsIgnoreCase("gil")) {
                dropText = quantity + " " + dropName;
            }
            else {
                dropText = dropName + " (" + quantity + ")";
            }

            dropsText.add(dropText);
            Log.d(LOG_TAG, "Parsed drop: " + dropText);
        }


        return dropsText;
    }

    private void createProxyNotification() {
        Intent intent = new Intent(getApplicationContext(), ProxyService.class);
        intent.setAction(getString(R.string.intent_stop_proxy));

        int random = (int)System.nanoTime();
        PendingIntent stopProxyIntent = PendingIntent.getService(
                getApplicationContext(),
                random,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT
        );

        random = (int)System.nanoTime();
        Intent settingsIntent = new Intent(getApplicationContext(), SettingsActivity.class);
        PendingIntent settingsPendingIntent = PendingIntent.getActivity(
                getApplicationContext(),
                random,
                settingsIntent,
                PendingIntent.FLAG_UPDATE_CURRENT
        );

        Notification.Builder notificationBuilder = new Notification.Builder(this.getApplicationContext())
                .setSmallIcon(R.drawable.ic_proxy_notification_icon)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.notification_proxy_enabled))
                .setContentIntent(settingsPendingIntent)
                .addAction(R.drawable.ic_stop_black_24dp, getString(R.string.notification_stop_proxy), stopProxyIntent)
                .setOngoing(true);

        NotificationManager notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationBuilder.setChannelId(LOG_TAG);

            int importance = NotificationManager.IMPORTANCE_DEFAULT;
            NotificationChannel channel = new NotificationChannel(LOG_TAG, LOG_TAG, importance);
            notificationManager.createNotificationChannel(channel);
        }

        notificationManager.notify(ProxyService.PROXY_NOTIFICATION_ID, notificationBuilder.build());
    }

    private boolean isUrlForFfrk(String url) {
        return url != null && ((url.contains("ffrk.denagames.com")) || (url.contains("dff.sp.mbga.jp")));
    }

    private boolean isGlobalUrl(String url) {
        return url != null && url.contains("ffrk.denagames.com");
    }

}