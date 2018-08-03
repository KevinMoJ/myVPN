package com.androapplite.shadowsocks.serverlist;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v4.widget.SwipeRefreshLayout;
import android.support.v7.app.AlertDialog;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.androapplite.shadowsocks.broadcast.Action;
import com.androapplite.shadowsocks.connect.ConnectVpnHelper;
import com.androapplite.shadowsocks.model.ServerConfig;
import com.androapplite.shadowsocks.model.VpnState;
import com.androapplite.shadowsocks.preference.DefaultSharedPrefeencesUtil;
import com.androapplite.shadowsocks.service.ServerListFetcherService;
import com.androapplite.shadowsocks.service.VpnManageService;
import com.androapplite.shadowsocks.utils.RuntimeSettings;
import com.androapplite.vpn3.R;
import com.vm.shadowsocks.core.LocalVpnService;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * Created by Kiven.Mo on 2018/7/5.
 */

public class FreeServerFragment extends Fragment implements SwipeRefreshLayout.OnRefreshListener, AdapterView.OnItemClickListener,
        DialogInterface.OnClickListener, AbsListView.OnScrollListener, View.OnClickListener {

    private SharedPreferences mPreferences;
    private SwipeRefreshLayout mSwipeRefreshLayout;
    private ArrayList<String> mNations;
    private ArrayList<String> mFlags;
    private HashMap<String, Integer> mSignalResIds;
    private ListView mListView;
    private View mTransparentView;
    private String mNation;
    private int mSelectedIndex;
    private boolean mHasServerJson;
    private AlertDialog mDisconnectDialog;
    private BroadcastReceiver mForgroundReceiver;
    private IntentFilter mForgroundReceiverIntentFilter;
    private FrameLayout mContainer;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.free_server_fragment, null);
        initView(view);
        initData(view);

        return view;
    }

    private void initData(View view) {
        initForegroundBroadcastIntentFilter();
        initForegroundBroadcastReceiver();
        LocalBroadcastManager.getInstance(getContext()).registerReceiver(mForgroundReceiver, mForgroundReceiverIntentFilter);

        parseServerList();

        String serverList = RuntimeSettings.getServerList();
        if (serverList != null && serverList.length() > 2) {
            mHasServerJson = true;
        } else {
            mHasServerJson = false;
        }
//        mHasServerJson = DefaultSharedPrefeencesUtil.getDefaultSharedPreferences(this).contains(SharedPreferenceKey.SERVER_LIST);
    }

    private void initView(View view) {
        mSwipeRefreshLayout = (SwipeRefreshLayout) view.findViewById(R.id.free_swipe_refresh);
        mSwipeRefreshLayout.setColorSchemeResources(android.R.color.holo_purple, android.R.color.holo_blue_bright, android.R.color.holo_orange_light,
                android.R.color.holo_red_light);
        mSwipeRefreshLayout.setOnRefreshListener(this);

        mTransparentView = view.findViewById(R.id.free_transparent_view);
        mTransparentView.setOnClickListener(this);

        mContainer = (FrameLayout) view.findViewById(R.id.free_ad_view_container);
        mListView = (ListView) view.findViewById(R.id.free_vpn_server_list);
        mListView.setAdapter(new ServerListAdapter());
        mListView.setOnItemClickListener(this);
        mListView.setOnScrollListener(this);
    }

    private void parseServerList() {
        if (isAdded()) {
            mNations = new ArrayList<>();
            mFlags = new ArrayList<>();
            mSignalResIds = new HashMap<>();
            mPreferences = DefaultSharedPrefeencesUtil.getDefaultSharedPreferences(getContext());
            mNation = RuntimeSettings.getVPNNation(getString(R.string.vpn_nation_opt));

            String serverListJson = RuntimeSettings.getServerList();
            ArrayList<ServerConfig> serverConfigs = null;
            if (serverListJson != null) {
                serverConfigs = ServerConfig.createServerList(getContext(), serverListJson);
            }

            if (serverConfigs != null && !serverConfigs.isEmpty()) {
                // 返回的服务器列表已经排序好了 是根据负载从低到高的顺序排列的，
                // 在这里遍历每一个服务器，然后添加到相应的国家，所以默认第一个就是负载最低的，
                // 如果负载最低的还是很高就默认为这个国家的所有服务器都满了
                // 不是VIP的界面就让他展示美国和德国的两个国家服务器
                for (ServerConfig serverConfig : serverConfigs) {
                    if (serverConfig.nation.equals(getResources().getString(R.string.vpn_nation_us))
                            || serverConfig.nation.equals(getResources().getString(R.string.vpn_nation_de))
                            || serverConfig.nation.equals(getResources().getString(R.string.vpn_nation_opt))) {

                        if (!mNations.contains(serverConfig.nation)) {
                            mNations.add(serverConfig.nation);
                            mFlags.add(serverConfig.flag);
                            mSignalResIds.put(serverConfig.nation, serverConfig.getSignalResId());
                        }
                    }
                }
            }

            mSelectedIndex = mNations.indexOf(mNation);
            if (mSelectedIndex == -1) mSelectedIndex = 0;
            mListView.setItemChecked(mSelectedIndex, true);
        }
    }


    public void disconnectToRefresh(String position) {
        if (LocalVpnService.IsRunning) {
            showDissConnectDialog();
        } else {
            mSwipeRefreshLayout.setRefreshing(true);
            mTransparentView.setVisibility(View.VISIBLE);
            ServerListFetcherService.fetchServerListAsync(getContext());
        }
    }

    @Override
    public void onRefresh() {
        disconnectToRefresh("下拉刷新");
    }

    private void initForegroundBroadcastIntentFilter() {
        mForgroundReceiverIntentFilter = new IntentFilter();
        mForgroundReceiverIntentFilter.addAction(Action.SERVER_LIST_FETCH_FINISH);
    }

    private void disconnectVpnServiceAsync() {
        if (LocalVpnService.IsRunning) {
            VpnManageService.stopVpnByUser();
            RuntimeSettings.setVPNState(VpnState.Stopped.ordinal());
            ConnectVpnHelper.getInstance(getContext()).clearErrorList();
            ConnectVpnHelper.getInstance(getContext()).release();
        }
    }

    private void showDissConnectDialog() {
        mDisconnectDialog = new AlertDialog.Builder(getContext())
                .setMessage(R.string.server_list_disconnect_title)
                .setPositiveButton(R.string.disconnect, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        disconnectVpnServiceAsync();
                        mSwipeRefreshLayout.setRefreshing(true);
                        mTransparentView.setVisibility(View.VISIBLE);
                        ServerListFetcherService.fetchServerListAsync(getContext());
                    }
                })
                .setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        mSwipeRefreshLayout.setRefreshing(false);
                        mTransparentView.setVisibility(View.GONE);
                    }
                })
                .setOnDismissListener(new DialogInterface.OnDismissListener() {
                    @Override
                    public void onDismiss(DialogInterface dialog) {
                        mDisconnectDialog = null;
                    }
                })
                .setCancelable(false)
                .show();
    }


    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.free_transparent_view:
                Toast.makeText(getContext(), R.string.updating_please_try_it_later, Toast.LENGTH_SHORT).show();
                break;
        }
    }

    private void initForegroundBroadcastReceiver() {
        mForgroundReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                switch (action) {
                    case Action.SERVER_LIST_FETCH_FINISH:
                        mSwipeRefreshLayout.setRefreshing(false);
                        mTransparentView.setVisibility(View.GONE);
                        parseServerList();
                        String serverList = RuntimeSettings.getServerList();
                        if (serverList != null && serverList.length() > 2) {
                            mHasServerJson = true;
                        } else {
                            mHasServerJson = false;
                        }
                        ((BaseAdapter) mListView.getAdapter()).notifyDataSetChanged();
                        break;
                }
            }
        };
    }

    class ServerListAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return mNations != null ? mNations.size() : 0;
        }

        @Override
        public Object getItem(int position) {
            return null;
        }

        @Override
        public long getItemId(int position) {
            return 0;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view;
            ViewHolder holder;
            final Context context = parent.getContext();
            if (convertView != null) {
                view = convertView;
                holder = (ViewHolder) view.getTag();
            } else {
                view = View.inflate(context, R.layout.item_popup_vpn_server, null);
                holder = new ViewHolder();
                holder.mFlagImageView = (ImageView) view.findViewById(R.id.vpn_icon);
                holder.mNationTextView = (TextView) view.findViewById(R.id.vpn_name);
                holder.mItemView = view.findViewById(R.id.vpn_server_list_item);
                holder.mSignalImageView = (ImageView) view.findViewById(R.id.signal);
                view.setTag(holder);
            }
            String flag = mFlags.get(position);
            int resid = context.getResources().getIdentifier(flag, "drawable", getContext().getPackageName());
            holder.mFlagImageView.setImageResource(resid);
            String nation = mNations.get(position);
            holder.mNationTextView.setText(nation);
//            if(nation.equals(mNation)) {
//                holder.mItemView.setSelected(true);
//                mSelectedIndex = position;
//            }else{
//                holder.mItemView.setSelected(false);
//            }
            if (mHasServerJson) {
                holder.mSignalImageView.setImageResource(mSignalResIds.get(nation));
                holder.mSignalImageView.setVisibility(View.VISIBLE);
            } else {
                holder.mSignalImageView.setVisibility(View.INVISIBLE);
            }
            return view;
        }

        class ViewHolder {
            ImageView mFlagImageView;
            TextView mNationTextView;
            View mItemView;
            ImageView mSignalImageView;
        }
    }

    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        if (!ConnectVpnHelper.isFreeUse(getContext(), ConnectVpnHelper.FREE_OVER_DIALOG_SERVER_LIST)) // 达到免费试用的时间
            return;

        mListView.setItemChecked(mSelectedIndex, false);
        mSelectedIndex = position;
        String nation = mNations.get(position);
        String flag = mFlags.get(position);
        int resid = mSignalResIds.get(mNations.get(position));
        if (resid == R.drawable.server_signal_full) {
            Toast.makeText(getContext(), R.string.server_list_full_toast, Toast.LENGTH_SHORT).show();
        } else {
            mListView.setItemChecked(position, true);
            RuntimeSettings.setVPNNation(nation);
            RuntimeSettings.setFlag(flag);
            getActivity().setResult(Activity.RESULT_OK);
            ConnectVpnHelper.getInstance(getContext()).release();
            mPreferences.edit().putInt("CLICK_SERVER_LIST_COUNT", mPreferences.getInt("CLICK_SERVER_LIST_COUNT", 0) + 1).apply();
            //不是小火箭加速的链接
            RuntimeSettings.setRocketSpeedConnect(false);
            getActivity().finish();
        }
    }

    @Override
    public void onClick(DialogInterface dialog, int which) {
        if (which == DialogInterface.BUTTON_POSITIVE) {
            mSwipeRefreshLayout.setRefreshing(true);
        } else if (which == DialogInterface.BUTTON_NEGATIVE) {
            mSwipeRefreshLayout.setRefreshing(false);
        }
    }

    @Override
    public void onScrollStateChanged(AbsListView view, int scrollState) {
        mSwipeRefreshLayout.setEnabled(scrollState == SCROLL_STATE_IDLE && view.getFirstVisiblePosition() == 0);
    }

    @Override
    public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount, int totalItemCount) {

    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mForgroundReceiver != null) {
            LocalBroadcastManager.getInstance(getContext()).unregisterReceiver(mForgroundReceiver);
        }
    }
}
