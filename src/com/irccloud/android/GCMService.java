/*
 * Copyright (c) 2015 IRCCloud, Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.irccloud.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.android.gms.gcm.GcmListenerService;
import com.google.android.gms.gcm.GoogleCloudMessaging;
import com.irccloud.android.data.collection.BuffersList;
import com.irccloud.android.data.collection.EventsList;
import com.irccloud.android.data.collection.NotificationsList;
import com.irccloud.android.data.model.Buffer;
import com.raizlabs.android.dbflow.runtime.TransactionManager;

import org.json.JSONObject;

import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

public class GCMService extends GcmListenerService {

    @Override
    public void onMessageReceived(String id, Bundle data) {
        super.onMessageReceived(id, data);

        if (!IRCCloudApplication.getInstance().getApplicationContext().getSharedPreferences("prefs", 0).getBoolean("gcm_registered", false)) {
            String regId = IRCCloudApplication.getInstance().getApplicationContext().getSharedPreferences("prefs", 0).getString("gcm_reg_id", "");
            if (regId.length() > 0) {
                scheduleUnregisterTimer(100, regId, false);
            }
        } else {
            //Log.d("IRCCloud", "GCM K/V pairs: " + data);
            try {
                if(!NetworkConnection.getInstance().ready)
                    NetworkConnection.getInstance().load();

                String type = data.getString("type");
                if (type != null && type.equalsIgnoreCase("heartbeat_echo") && data.getString("seenEids") != null) {
                    NetworkConnection conn = NetworkConnection.getInstance();
                    ObjectMapper mapper = new ObjectMapper();
                    JsonParser parser = mapper.getFactory().createParser(data.getString("seenEids"));
                    JsonNode seenEids = mapper.readTree(parser);
                    Iterator<Map.Entry<String, JsonNode>> iterator = seenEids.fields();
                    while (iterator.hasNext()) {
                        Map.Entry<String, JsonNode> entry = iterator.next();
                        JsonNode eids = entry.getValue();
                        Iterator<Map.Entry<String, JsonNode>> j = eids.fields();
                        while (j.hasNext()) {
                            Map.Entry<String, JsonNode> eidentry = j.next();
                            String bid = eidentry.getKey();
                            long eid = eidentry.getValue().asLong();
                            if (conn.ready && conn.getState() != NetworkConnection.STATE_CONNECTED) {
                                Buffer b = BuffersList.getInstance().getBuffer(Integer.valueOf(bid));
                                if (b != null) {
                                    b.setLast_seen_eid(eid);
                                    if (EventsList.getInstance().lastEidForBuffer(b.getBid()) <= eid) {
                                        b.setUnread(0);
                                        b.setHighlights(0);
                                        TransactionManager.getInstance().saveOnSaveQueue(b);
                                    }
                                }
                            }
                            NotificationsList.getInstance().deleteOldNotifications(Integer.valueOf(bid), eid);
                            NotificationsList.getInstance().updateLastSeenEid(Integer.valueOf(bid), eid);
                        }
                    }
                    parser.close();
                } else {
                    int cid = Integer.valueOf(data.getString("cid"));
                    int bid = Integer.valueOf(data.getString("bid"));
                    long eid = Long.valueOf(data.getString("eid"));
                    if (NotificationsList.getInstance().getNotification(eid) != null) {
                        Log.e("IRCCloud", "GCM got EID that already exists");
                        return;
                    }

                    if(NetworkConnection.getInstance().getState() == NetworkConnection.STATE_DISCONNECTED && BuffersList.getInstance().getBuffer(bid) != null)
                        NetworkConnection.getInstance().request_backlog(cid, bid, 0);

                    String from = data.getString("from_nick");
                    String msg = data.getString("msg");
                    if (msg != null)
                        msg = ColorFormatter.html_to_spanned(ColorFormatter.irc_to_html(TextUtils.htmlEncode(msg))).toString();
                    String chan = data.getString("chan");
                    if (chan == null)
                        chan = "";
                    String buffer_type = data.getString("buffer_type");
                    String server_name = data.getString("server_name");
                    if (server_name == null || server_name.length() == 0)
                        server_name = data.getString("server_hostname");

                    String network = NotificationsList.getInstance().getNetwork(cid).name;
                    if (network == null)
                        NotificationsList.getInstance().addNetwork(cid, server_name);

                    NotificationsList.getInstance().addNotification(cid, bid, eid, from, msg, chan, buffer_type, type);

                    if (from == null || from.length() == 0)
                        NotificationsList.getInstance().showNotifications(server_name + ": " + msg);
                    else if (buffer_type != null && buffer_type.equals("channel")) {
                        if (type != null && type.equals("buffer_me_msg"))
                            NotificationsList.getInstance().showNotifications(chan + ": — " + from + " " + msg);
                        else
                            NotificationsList.getInstance().showNotifications(chan + ": <" + from + "> " + msg);
                    } else {
                        if (type != null && type.equals("buffer_me_msg"))
                            NotificationsList.getInstance().showNotifications("— " + from + " " + msg);
                        else
                            NotificationsList.getInstance().showNotifications(from + ": " + msg);
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
                Log.w("IRCCloud", "Unable to parse GCM message");
            }
        }
    }

    private static final Timer GCMTimer = new Timer("GCM-Registration-Timer");

    public static void scheduleRegisterTimer(int delay) {
        final int retrydelay = (delay < 500) ? 500 : delay;

        GCMTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                if (!IRCCloudApplication.getInstance().getApplicationContext().getSharedPreferences("prefs", 0).contains("session_key")) {
                    return;
                }
                boolean success = false;
                if (getRegistrationId(IRCCloudApplication.getInstance().getApplicationContext()).length() == 0) {
                    try {
                        String oldRegId = IRCCloudApplication.getInstance().getApplicationContext().getSharedPreferences("prefs", 0).getString("gcm_reg_id", "");
                        String regId = GoogleCloudMessaging.getInstance(IRCCloudApplication.getInstance().getApplicationContext()).register(BuildConfig.GCM_ID);
                        int appVersion = getAppVersion();
                        Log.i("IRCCloud", "Saving regId on app version " + appVersion);
                        SharedPreferences.Editor editor = IRCCloudApplication.getInstance().getApplicationContext().getSharedPreferences("prefs", 0).edit();
                        editor.putString("gcm_reg_id", regId);
                        editor.putInt("gcm_app_version", appVersion);
                        editor.putString("gcm_app_build", Build.FINGERPRINT);
                        editor.remove("gcm_registered");
                        editor.commit();
                        if (oldRegId.length() > 0 && !oldRegId.equals(regId)) {
                            Log.i("IRCCloud", "Unregistering old ID");
                            scheduleUnregisterTimer(1000, oldRegId, true);
                        }
                    } catch (IOException ex) {
                        ex.printStackTrace();
                        Log.w("IRCCloud", "Failed to register device ID, will retry in " + ((retrydelay * 2) / 1000) + " seconds");
                        scheduleRegisterTimer(retrydelay * 2);
                        return;
                    } catch (SecurityException e) {
                        //User has blocked GCM via AppOps
                        return;
                    }
                }
                Log.i("IRCCloud", "Sending GCM ID to IRCCloud");
                try {
                    JSONObject result = NetworkConnection.getInstance().registerGCM(getRegistrationId(IRCCloudApplication.getInstance().getApplicationContext()), IRCCloudApplication.getInstance().getApplicationContext().getSharedPreferences("prefs", 0).getString("session_key", ""));
                    if (result.has("success"))
                        success = result.getBoolean("success");
                } catch (Exception e) {
                    e.printStackTrace();
                }
                if (success) {
                    SharedPreferences.Editor editor = IRCCloudApplication.getInstance().getApplicationContext().getSharedPreferences("prefs", 0).edit();
                    editor.putBoolean("gcm_registered", true);
                    editor.commit();
                    Log.d("IRCCloud", "Device successfully registered");
                } else {
                    Log.w("IRCCloud", "Failed to register device ID, will retry in " + ((retrydelay * 2) / 1000) + " seconds");
                    scheduleRegisterTimer(retrydelay * 2);
                }
            }

        }, delay);

    }

    public static void scheduleUnregisterTimer(int delay, final String regId, final boolean serverOnly) {
        final int retrydelay = (delay < 500) ? 500 : delay;

        GCMTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                boolean success = false;
                if (!serverOnly) {
                    try {
                        GoogleCloudMessaging.getInstance(IRCCloudApplication.getInstance().getApplicationContext()).unregister();
                    } catch (IOException e) {
                        Log.w("IRCCloud", "Failed to unregister device ID from GCM, will retry in " + ((retrydelay * 2) / 1000) + " seconds");
                        scheduleUnregisterTimer(retrydelay * 2, regId, false);
                        return;
                    } catch (SecurityException e) {
                        //User has blocked GCM via AppOps
                    }
                }
                SharedPreferences.Editor editor = IRCCloudApplication.getInstance().getApplicationContext().getSharedPreferences("prefs", 0).edit();
                String session = null;
                try {
                    session = IRCCloudApplication.getInstance().getApplicationContext().getSharedPreferences("prefs", 0).getString(regId, "");
                    if (session.length() > 0) {
                        JSONObject result = NetworkConnection.getInstance().unregisterGCM(regId, session);
                        if (result.has("message") && result.getString("message").equals("auth"))
                            success = true;
                        else if (result.has("success"))
                            success = result.getBoolean("success");
                        if (success)
                            NetworkConnection.getInstance().logout(session);
                    } else {
                        success = true;
                    }
                } catch (Exception e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                }
                if (success) {
                    editor.remove(regId);
                    if (session != null && session.length() > 0 && regId.equals(IRCCloudApplication.getInstance().getApplicationContext().getSharedPreferences("prefs", 0).getString("gcm_reg_id", ""))) {
                        editor.remove("gcm_registered");
                        editor.remove("gcm_reg_id");
                        editor.remove("gcm_app_version");
                    }
                    Log.d("IRCCloud", "Device successfully unregistered");
                } else {
                    Log.w("IRCCloud", "Failed to unregister device ID from IRCCloud, will retry in " + ((retrydelay * 2) / 1000) + " seconds");
                    scheduleUnregisterTimer(retrydelay * 2, regId, true);
                }
                editor.commit();
            }
        }, delay);
    }

    private static int getAppVersion() {
        try {
            Context context = IRCCloudApplication.getInstance().getApplicationContext();
            PackageInfo packageInfo = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            return packageInfo.versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            // should never happen
            throw new RuntimeException("Could not get package name: " + e);
        }
    }

    public static String getRegistrationId(Context context) {
        final SharedPreferences prefs = context.getSharedPreferences("prefs", 0);
        String registrationId = prefs.getString("gcm_reg_id", "");
        if (registrationId.length() == 0) {
            Log.i("IRCCloud", "Registration not found.");
            return "";
        }
        // Check if app was updated; if so, it must clear the registration ID
        // since the existing regID is not guaranteed to work with the new
        // app version.
        int registeredVersion = prefs.getInt("gcm_app_version", Integer.MIN_VALUE);
        int currentVersion = getAppVersion();
        if (registeredVersion != currentVersion) {
            Log.i("IRCCloud", "App version changed.");
            return "";
        }
        String build = prefs.getString("gcm_app_build", "");
        if (!Build.FINGERPRINT.equals(build)) {
            Log.i("IRCCloud", "OS version changed.");
            return "";
        }
        return registrationId;
    }
}