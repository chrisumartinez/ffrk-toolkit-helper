package com.ffrktoolkit.ffrktoolkithelper;

import android.support.design.widget.FloatingActionButton;
import android.support.design.widget.Snackbar;
import android.support.design.widget.TabLayout;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;

import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentPagerAdapter;
import android.support.v4.view.ViewPager;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;

import android.widget.TextView;

import com.ffrktoolkit.ffrktoolkithelper.fragments.ProxyAndDataFragment;
import com.ffrktoolkit.ffrktoolkithelper.views.ViewPagerAdapter;

public class FFRKToolkitHelperActivity extends AppCompatActivity {

    /**
     * The {@link ViewPager} that will host the section contents.
     */
    private ViewPager mViewPager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ffrktoolkit_helper);

        // Set up the ViewPager with the sections adapter.
        mViewPager = (ViewPager) findViewById(R.id.container);

        ViewPagerAdapter viewPagerAdapter = new ViewPagerAdapter(getSupportFragmentManager());
        viewPagerAdapter.addFragment(ProxyAndDataFragment.newInstance(), getString(R.string.pref_header_proxy_and_data));
        //viewPagerAdapter.addFragment(ProxyAndDataFragment.newInstance(), getString(R.string.pref_header_proxy_and_data));
        mViewPager.setAdapter(viewPagerAdapter);
        ((TabLayout) findViewById(R.id.tab_layout)).setupWithViewPager(mViewPager);
    }
}
