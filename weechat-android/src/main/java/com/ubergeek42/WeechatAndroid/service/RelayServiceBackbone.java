/*******************************************************************************
 * Copyright 2012 Keith Johnson
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
package com.ubergeek42.WeechatAndroid.service;

import java.io.File;
import java.security.cert.X509Certificate;
import java.util.HashSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.os.IBinder;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.support.v4.app.NotificationCompat;

import com.ubergeek42.WeechatAndroid.BuildConfig;
import com.ubergeek42.WeechatAndroid.R;
import com.ubergeek42.WeechatAndroid.WeechatActivity;
import com.ubergeek42.WeechatAndroid.WeechatPreferencesActivity;
import com.ubergeek42.WeechatAndroid.notifications.HotlistObserver;
import com.ubergeek42.weechat.relay.RelayConnection;
import com.ubergeek42.weechat.relay.RelayConnectionHandler;
import com.ubergeek42.weechat.relay.RelayMessageHandler;
import com.ubergeek42.weechat.relay.connection.IConnection;
import com.ubergeek42.weechat.relay.connection.PlainConnection;
import com.ubergeek42.weechat.relay.connection.SSHConnection;
import com.ubergeek42.weechat.relay.connection.SSLConnection;
import com.ubergeek42.weechat.relay.connection.StunnelConnection;
import com.ubergeek42.weechat.relay.messagehandler.UpgradeHandler;
import com.ubergeek42.weechat.relay.messagehandler.UpgradeObserver;
import com.ubergeek42.weechat.relay.protocol.RelayObject;

public class RelayServiceBackbone extends Service implements RelayConnectionHandler,
        OnSharedPreferenceChangeListener, HotlistObserver, UpgradeObserver {

    private static Logger logger = LoggerFactory.getLogger("RelayServiceBackbone");
    final private static boolean DEBUG = BuildConfig.DEBUG && true;

    private static final int NOTIFICATION_ID = 42;
    private NotificationManager notificationManger;

    boolean optimize_traffic = false;
    
    String host;
    int port;
    String pass;

    String stunnelCert;
    String stunnelPass;

    String sshHost;
    String sshPass;
    String sshPort;
    String sshUser;
    String sshKeyfile;

    RelayConnection connection;
    HashSet<RelayConnectionHandler> connectionHandlers = new HashSet<RelayConnectionHandler>();
    SharedPreferences prefs;
    private boolean shutdown;
    private boolean disconnected;
    private Thread reconnector = null;
    private Thread upgrading;

    SSLHandler certmanager;
    X509Certificate untrustedCert;

    // for some reason, this java can't have binary literals...
    public final static int DISCONNECTED =   Integer.parseInt("00001", 2);
    public final static int CONNECTING =     Integer.parseInt("00010", 2);
    public final static int CONNECTED =      Integer.parseInt("00100", 2);
    public final static int AUTHENTICATED =  Integer.parseInt("01000", 2);
    public final static int BUFFERS_LISTED = Integer.parseInt("10000", 2);
    int connection_status = DISCONNECTED;

    /** check status of connection
     ** @param status one of DISCONNECTED, CONNECTING, CONNECTED, AUTHENTICATED, BUFFERS_LISTED
     ** @return true if connection corresponds to one of these */
    public boolean isConnection(int status) {
        return (connection_status & status) != 0;
    }

    @Override
    public void onCreate() {
        if (DEBUG) logger.debug("onCreate()");
        super.onCreate();

        prefs = PreferenceManager.getDefaultSharedPreferences(getBaseContext());
        prefs.registerOnSharedPreferenceChangeListener(this);

        notificationManger = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        //showNotification(null, "Tap to connect");



        startForeground(NOTIFICATION_ID, buildNotification(null,"Tap to connect", null));

        disconnected = false;

        // Prepare for dealing with SSL certs
        certmanager = new SSLHandler(new File(getDir("sslDir", Context.MODE_PRIVATE), "keystore.jks"));
        
        if (prefs.getBoolean("autoconnect", false)) {
            connect();
        }
    }

    // TODO: decide whether killing the process is necessary...
    @Override
    public void onDestroy() {
        if (DEBUG) logger.debug("onDestroy()");
        notificationManger.cancel(NOTIFICATION_ID);
        super.onDestroy();
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    /** overridden */
    @Override
    public IBinder onBind(Intent intent) {return null;}

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (DEBUG) logger.debug("onStartCommand()");
        if (intent != null) {
            // Autoconnect if possible
            // if (prefs.getBoolean("autoconnect", false)) {
            // connect();
            // }
        }
        return START_STICKY;
    }

    public boolean connect() {
        // Load the preferences
        host = prefs.getString("host", null);
        pass = prefs.getString("password", "password");
        port = Integer.parseInt(prefs.getString("port", "8001"));

        stunnelCert = prefs.getString("stunnel_cert", "");
        stunnelPass = prefs.getString("stunnel_pass", "");

        sshHost = prefs.getString("ssh_host", "");
        sshUser = prefs.getString("ssh_user", "");
        sshPass = prefs.getString("ssh_pass", "");
        sshPort = prefs.getString("ssh_port", "22");
        sshKeyfile = prefs.getString("ssh_keyfile", "");
        
        optimize_traffic = prefs.getBoolean("optimize_traffic", false);

        // If no host defined, signal them to edit their preferences
        if (host == null) {
            Intent i = new Intent(this, WeechatPreferencesActivity.class);
            PendingIntent contentIntent = PendingIntent.getActivity(this, 0, i,
                    PendingIntent.FLAG_CANCEL_CURRENT);

            showNotification(getString(R.string.notification_update_settings_details),
                             getString(R.string.notification_update_settings),
                             contentIntent);
            return false;
        }

        // Only connect if we aren't already connected
        if ((connection != null) && (connection.isConnected())) {
            return false;
        }

        shutdown = false;

        IConnection conn;
        String connType = prefs.getString("connection_type", "plain");
        if (connType.equals("ssh")) {
            SSHConnection tmp = new SSHConnection(host, port);
            tmp.setSSHHost(sshHost);
            tmp.setSSHUsername(sshUser);
            tmp.setSSHKeyFile(sshKeyfile);
            tmp.setSSHPassword(sshPass);
            conn = tmp;
        } else if (connType.equals("stunnel")) {
            StunnelConnection tmp = new StunnelConnection(host, port);
            tmp.setStunnelCert(stunnelCert);
            tmp.setStunnelKey(stunnelPass);
            conn = tmp;
        } else if (connType.equals("ssl")) {
            SSLConnection tmp = new SSLConnection(host, port);
            tmp.setSSLKeystore(certmanager.sslKeystore);
            conn = tmp;
        } else {
            PlainConnection pc = new PlainConnection(host, port);
            conn = pc;
        }

        connection = new RelayConnection(conn, pass);
        conn.addConnectionHandler(this);

        connection.connect();
        return true;
    }

    void resetNotification() {
        showNotification(null, getString(R.string.notification_connected_to) + host);
    }

    private Notification buildNotification(String tickerText, String content, PendingIntent intent) {
        PendingIntent contentIntent;
        if (intent == null) {
            contentIntent = PendingIntent.getActivity(this, 0, new Intent(this, WeechatActivity.class), PendingIntent.FLAG_CANCEL_CURRENT);
        } else {
            contentIntent = intent;
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this);
        builder.setContentIntent(contentIntent).setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.app_version)).setContentText(content)
                .setTicker(tickerText).setWhen(System.currentTimeMillis());

        return builder.getNotification();
    }

    private void showNotification(String tickerText, String content, PendingIntent intent) {
        notificationManger.notify(NOTIFICATION_ID, buildNotification(tickerText, content, intent));
    }
    private void showNotification(String tickerText, String content) {
        notificationManger.notify(NOTIFICATION_ID, buildNotification(tickerText, content, null));
    }

    // Spawn a thread that attempts to reconnect us.
    private void reconnect() {
        // stop if we are already trying to reconnect
        if (reconnector != null && reconnector.isAlive()) {
            return;
        }
        reconnector = new Thread(new Runnable() {
            long delays[] = new long[] { 0, 5, 15, 30, 60, 120, 300, 600, 900 };
            int numReconnects = 0;// how many times we've tried this...

            @Override
            public void run() {
                for (;;) {
                    long currentDelay = 0;
                    if (numReconnects >= delays.length) {
                        currentDelay = delays[delays.length - 1];
                    } else {
                        currentDelay = delays[numReconnects];
                    }
                    if (currentDelay > 0) {
                        showNotification(getString(R.string.notification_reconnecting), String.format(getString(R.string.notification_reconnecting_details),currentDelay));
                    }
                    // Sleep for a bit
                    SystemClock.sleep(currentDelay * 1000);

                    // See if we are connected, if so we can stop trying to reconnect
                    if (connection != null && connection.isConnected()) {
                        return;
                    }

                    // Try connecting again
                    connect();
                    numReconnects++;
                }
            }
        });
        reconnector.start();
    }

    @Override
    public void onConnecting() {connection_status = CONNECTING;}

    @Override
    public void onConnect() {connection_status = CONNECTED;}

    @Override
    public void onAuthenticated() {
        connection_status = CONNECTED | AUTHENTICATED;

        if (disconnected) {
            showNotification(getString(R.string.notification_reconnected_to) + host, getString(R.string.notification_connected_to) + host);
        } else {
            String tmp = getString(R.string.notification_connected_to) + host;
            showNotification(tmp,tmp);
        }
        disconnected = false;

        startHandlingBoneEvents();

        // Handle weechat upgrading
        UpgradeHandler uh = new UpgradeHandler(this);
        connection.addHandler("_upgrade", uh);
        connection.addHandler("_upgrade_ended", uh);

        connection.addHandler("listbuffers", new BuffersListedObserver());

        if (!optimize_traffic)
            connection.sendMsg("sync");

        for (RelayConnectionHandler rch : connectionHandlers) {
            rch.onConnect();
        }
    }

    void startHandlingBoneEvents() {}

    @Override
    public void onBuffersListed() {
        connection_status = CONNECTED | AUTHENTICATED | BUFFERS_LISTED;
        for (RelayConnectionHandler rch : connectionHandlers) {
            rch.onBuffersListed();
        }
    }

    @Override
    public void onDisconnect() {
        connection_status = DISCONNECTED;
        if (disconnected) {
            return; // Only do the disconnect handler once
        }
        // :( aww disconnected
        for (RelayConnectionHandler rch : connectionHandlers) {
            rch.onDisconnect();
        }

        disconnected = true;

        // Automatically attempt reconnection if enabled(and if we aren't shutting down)
        if (!shutdown && prefs.getBoolean("reconnect", true)) {
            reconnect();
            String tmp = getString(R.string.notification_reconnecting);
            showNotification(tmp,tmp);
        } else {
            String tmp = getString(R.string.notification_disconnected);
            showNotification(tmp,tmp);
        }
    }

    @Override
    public void onError(String error, Object extraData) {
        logger.error("relayservice - error!");
        for (RelayConnectionHandler rch : connectionHandlers) {
            rch.onError(error, extraData);
        }
    }

    public void shutdown() {
        if (connection != null) {
            shutdown = true;
            // Do the actual shutdown on its own thread(to avoid an error on Android 3.0+)
            new Thread(new Runnable() {
                @Override
                public void run() {
                    connection.disconnect();
                }
            }).start();
        }
        // TODO: possibly call stopself?
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        // Load/refresh preferences
        if (key.equals("host")) {
            host = prefs.getString("host", null);
        } else if (key.equals("password")) {
            pass = prefs.getString("password", "password");
        } else if (key.equals("port")) {
            port = Integer.parseInt(prefs.getString("port", "8001"));
        } else if (key.equals("optimize_traffic")) {
            optimize_traffic = prefs.getBoolean("optimize_traffic", false);
        }
    }

    @Override
    public void onHighlight(String bufferName, String message) {
        Intent i = new Intent(this, WeechatActivity.class);
        i.putExtra("buffer", bufferName);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, i,
                PendingIntent.FLAG_UPDATE_CURRENT);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this);
        builder.setContentIntent(contentIntent).setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.app_version)).setContentText(message)
                .setTicker(message).setWhen(System.currentTimeMillis());

        Notification notification = builder.getNotification();
        notification.flags |= Notification.FLAG_ONGOING_EVENT | Notification.FLAG_NO_CLEAR;

        // Default notification sound if enabled
        if (prefs.getBoolean("notification_sounds", false)) {
            notification.defaults |= Notification.DEFAULT_SOUND;
        }

        notificationManger.notify(NOTIFICATION_ID, notification);
    }

    @Override
    public void onUpgrade() {
        // Don't do this twice
        if (upgrading != null && upgrading.isAlive()) {
            return;
        }

        // Basically just reconnect on upgrade
        upgrading = new Thread(new Runnable() {
            @Override
            public void run() {
                showNotification(getString(R.string.notification_upgrading),
                        getString(R.string.notification_upgrading_details));
                shutdown();
                SystemClock.sleep(5000);
                connect();
            }
        });
        upgrading.start();
    }

//    public void requestNicklist(String bufferPointerOrName) {
//        connection.sendMsg("nicklist", "nicklist", bufferPointerOrName);
//    }

    private class BuffersListedObserver implements RelayMessageHandler {
        @Override
        public void handleMessage(RelayObject obj, String id) {
            RelayServiceBackbone.this.onBuffersListed();
        }
    }
}
