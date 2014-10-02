/*
 * Copyright (C) 2014 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.musicservicedemo;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.AsyncTask;
import android.util.SparseArray;

import com.example.android.musicservicedemo.utils.BitmapHelper;
import com.example.android.musicservicedemo.utils.LogHelper;

import java.io.IOException;
import java.util.LinkedHashMap;

/**
 * Keeps track of a notification and updates it automatically for a given
 * MediaSession. Maintaining a visible notification (usually) guarantees that the music service
 * won't be killed during playback.
 */
public class MediaNotification extends BroadcastReceiver {
    private static final String TAG = "MediaNotification";

    private static final int NOTIFICATION_ID = 412;

    public static final String ACTION_PAUSE = "com.example.android.musicservicedemo.pause";
    public static final String ACTION_PLAY = "com.example.android.musicservicedemo.play";
    public static final String ACTION_PREV = "com.example.android.musicservicedemo.prev";
    public static final String ACTION_NEXT = "com.example.android.musicservicedemo.next";


    private final MusicService mService;
    private MediaSession.Token mSessionToken;
    private MediaController mController;
    private MediaController.TransportControls mTransportControls;
    private final SparseArray<PendingIntent> mIntents = new SparseArray<PendingIntent>();
    private final LinkedHashMap<String, Bitmap> mAlbumArtCache;

    private PlaybackState mPlaybackState;
    private MediaMetadata mMetadata;

    private Notification.Builder mNotificationBuilder;
    private NotificationManager mNotificationManager;
    private Notification.Action mPlayPauseAction;

    private String mCurrentAlbumArt;
    private int mNotificationColor;

    private boolean mStarted = false;

    public MediaNotification(MusicService service) {
        mService = service;
        updateSessionToken();

        // simple album art cache with up to 10 last accessed elements:
        mAlbumArtCache = new LinkedHashMap<String, Bitmap>(10, 1f, true) {
            @Override
            protected boolean removeEldestEntry(Entry eldest) {
                return size() > 10;
            }
        };

        mNotificationColor = getNotificationColor();

        mNotificationManager = (NotificationManager) mService
                .getSystemService(Context.NOTIFICATION_SERVICE);

        String pkg = mService.getPackageName();
        mIntents.put(R.drawable.ic_pause_white_24dp, PendingIntent.getBroadcast(mService, 100,
                new Intent(ACTION_PAUSE).setPackage(pkg), PendingIntent.FLAG_CANCEL_CURRENT));
        mIntents.put(R.drawable.ic_play_arrow_white_24dp, PendingIntent.getBroadcast(mService, 100,
                new Intent(ACTION_PLAY).setPackage(pkg), PendingIntent.FLAG_CANCEL_CURRENT));
        mIntents.put(R.drawable.ic_skip_previous_white_24dp, PendingIntent.getBroadcast(mService, 100,
                new Intent(ACTION_PREV).setPackage(pkg), PendingIntent.FLAG_CANCEL_CURRENT));
        mIntents.put(R.drawable.ic_skip_next_white_24dp, PendingIntent.getBroadcast(mService, 100,
                new Intent(ACTION_NEXT).setPackage(pkg), PendingIntent.FLAG_CANCEL_CURRENT));
    }

    protected int getNotificationColor() {
        int notificationColor = 0;
        String packageName = mService.getPackageName();
        try {
            Context packageContext = mService.createPackageContext(packageName, 0);
            ApplicationInfo applicationInfo =
                    mService.getPackageManager().getApplicationInfo(packageName, 0);
            packageContext.setTheme(applicationInfo.theme);
            Resources.Theme theme = packageContext.getTheme();
            TypedArray ta = theme.obtainStyledAttributes(
                    new int[] {android.R.attr.colorPrimary});
            notificationColor = ta.getColor(0, Color.DKGRAY);
            ta.recycle();
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }
        return notificationColor;
    }

    /**
     * Posts the notification and starts tracking the session to keep it
     * updated. The notification will automatically be removed if the session is
     * destroyed before {@link #stopNotification} is called.
     */
    public void startNotification() {
        if (!mStarted) {
            mController.registerCallback(mCb);
            IntentFilter filter = new IntentFilter();
            filter.addAction(ACTION_NEXT);
            filter.addAction(ACTION_PAUSE);
            filter.addAction(ACTION_PLAY);
            filter.addAction(ACTION_PREV);
            mService.registerReceiver(this, filter);

            mMetadata = mController.getMetadata();
            mPlaybackState = mController.getPlaybackState();

            mStarted = true;
            // The notification must be updated after setting started to true
            updateNotificationMetadata();
        }
    }

    /**
     * Removes the notification and stops tracking the session. If the session
     * was destroyed this has no effect.
     */
    public void stopNotification() {
        mStarted = false;
        mController.unregisterCallback(mCb);
        try {
            mService.unregisterReceiver(this);
        } catch (IllegalArgumentException ex) {
            // ignore if the receiver is not registered.
        }
        mService.stopForeground(true);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        final String action = intent.getAction();
        LogHelper.d(TAG, "Received intent with action " + action);
        if (ACTION_PAUSE.equals(action)) {
            mTransportControls.pause();
        } else if (ACTION_PLAY.equals(action)) {
            mTransportControls.play();
        } else if (ACTION_NEXT.equals(action)) {
            mTransportControls.skipToNext();
        } else if (ACTION_PREV.equals(action)) {
            mTransportControls.skipToPrevious();
        }
    }

    /**
     * Update the state based on a change on the session token. Called either when
     * we are running for the first time or when the media session owner has destroyed the session
     * (see {@link android.media.session.MediaController.Callback#onSessionDestroyed()})
     */
    private void updateSessionToken() {
        MediaSession.Token freshToken = mService.getSessionToken();
        if (mSessionToken == null || !mSessionToken.equals(freshToken)) {
            if (mController != null) {
                mController.unregisterCallback(mCb);
            }
            mSessionToken = freshToken;
            mController = new MediaController(mService, mSessionToken);
            mTransportControls = mController.getTransportControls();
            if (mStarted) {
                mController.registerCallback(mCb);
            }
        }
    }

    private final MediaController.Callback mCb = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            mPlaybackState = state;
            LogHelper.d(TAG, "Received new playback state", state);
            updateNotificationPlaybackState();
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            mMetadata = metadata;
            LogHelper.d(TAG, "Received new metadata ", metadata);
            updateNotificationMetadata();
        }

        @Override
        public void onSessionDestroyed() {
            super.onSessionDestroyed();
            LogHelper.d(TAG, "Session was destroyed, resetting to the new session token");
            updateSessionToken();
        }
    };

    private void updateNotificationMetadata() {
        LogHelper.d(TAG, "updateNotificationMetadata. mMetadata=" + mMetadata);
        if (mMetadata == null || mPlaybackState == null) {
            return;
        }

        updatePlayPauseAction();

        mNotificationBuilder = new Notification.Builder(mService);
        int playPauseActionIndex = 0;

        // If skip to previous action is enabled
        if ((mPlaybackState.getActions() & PlaybackState.ACTION_SKIP_TO_PREVIOUS) != 0) {
            mNotificationBuilder
                    .addAction(R.drawable.ic_skip_previous_white_24dp,
                            mService.getString(R.string.label_previous),
                            mIntents.get(R.drawable.ic_skip_previous_white_24dp));
            playPauseActionIndex = 1;
        }

        mNotificationBuilder.addAction(mPlayPauseAction);

        // If skip to next action is enabled
        if ((mPlaybackState.getActions() & PlaybackState.ACTION_SKIP_TO_NEXT) != 0) {
            mNotificationBuilder.addAction(R.drawable.ic_skip_next_white_24dp,
                    mService.getString(R.string.label_next),
                    mIntents.get(R.drawable.ic_skip_next_white_24dp));
        }

        MediaDescription description = mMetadata.getDescription();
        Bitmap art = description.getIconBitmap();
        if (art == null && description.getIconUri() != null) {
            // This sample assumes the iconUri will be a valid URL formatted String, but
            // it can actually be any valid Android Uri formatted String.
            // async fetch the album art icon
            String artUrl = description.getIconUri().toString();
            art = mAlbumArtCache.get(artUrl);
            if (art == null) {
                fetchBitmapFromURLAsync(artUrl);
            } else {
                mNotificationBuilder.setLargeIcon(art);
            }
        }

        mNotificationBuilder
                .setStyle(new Notification.MediaStyle()
                        .setShowActionsInCompactView(playPauseActionIndex)  // only show play/pause in compact view
                        .setMediaSession(mSessionToken))
                .setColor(mNotificationColor)
                .setSmallIcon(R.drawable.ic_notification)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setUsesChronometer(true)
                .setContentTitle(description.getTitle())
                .setContentText(description.getSubtitle())
                .setLargeIcon(art);

        updateNotificationPlaybackState();

        mService.startForeground(NOTIFICATION_ID, mNotificationBuilder.build());
    }

    private void updatePlayPauseAction() {
        LogHelper.d(TAG, "updatePlayPauseAction");
        String playPauseLabel = "";
        int playPauseIcon;
        if (mPlaybackState.getState() == PlaybackState.STATE_PLAYING) {
            playPauseLabel = mService.getString(R.string.label_pause);
            playPauseIcon = R.drawable.ic_pause_white_24dp;
        } else {
            playPauseLabel = mService.getString(R.string.label_play);
            playPauseIcon = R.drawable.ic_play_arrow_white_24dp;
        }
        if (mPlayPauseAction == null) {
            mPlayPauseAction = new Notification.Action(playPauseIcon, playPauseLabel,
                    mIntents.get(playPauseIcon));
        } else {
            mPlayPauseAction.icon = playPauseIcon;
            mPlayPauseAction.title = playPauseLabel;
            mPlayPauseAction.actionIntent = mIntents.get(playPauseIcon);
        }
    }

    private void updateNotificationPlaybackState() {
        LogHelper.d(TAG, "updateNotificationPlaybackState. mPlaybackState=" + mPlaybackState);
        if (mPlaybackState == null || !mStarted) {
            LogHelper.d(TAG, "updateNotificationPlaybackState. cancelling notification!");
            mService.stopForeground(true);
            return;
        }
        if (mNotificationBuilder == null) {
            LogHelper.d(TAG, "updateNotificationPlaybackState. there is no notificationBuilder. Ignoring request to update state!");
            return;
        }
        if (mPlaybackState.getPosition() >= 0) {
            LogHelper.d(TAG, "updateNotificationPlaybackState. updating playback position to ",
                    (System.currentTimeMillis() - mPlaybackState.getPosition()) / 1000, " seconds");
            mNotificationBuilder
                    .setWhen(System.currentTimeMillis() - mPlaybackState.getPosition())
                    .setShowWhen(true)
                    .setUsesChronometer(true);
            mNotificationBuilder.setShowWhen(true);
        } else {
            LogHelper.d(TAG, "updateNotificationPlaybackState. hiding playback position");
            mNotificationBuilder
                    .setWhen(0)
                    .setShowWhen(false)
                    .setUsesChronometer(false);
        }

        updatePlayPauseAction();

        mNotificationManager.notify(NOTIFICATION_ID, mNotificationBuilder.build());
    }

    public void fetchBitmapFromURLAsync(final String source) {
        LogHelper.d(TAG, "getBitmapFromURLAsync: starting asynctask to fetch ", source);
        new AsyncTask() {
            @Override
            protected Object doInBackground(Object[] objects) {
                try {
                    Bitmap bitmap = BitmapHelper.fetchAndRescaleBitmap(source,
                            BitmapHelper.MEDIA_ART_BIG_WIDTH, BitmapHelper.MEDIA_ART_BIG_HEIGHT);
                    mAlbumArtCache.put(source, bitmap);
                    if (mMetadata != null) {
                        String currentSource = mMetadata.getDescription().getIconUri().toString();
                        // If the media is still the same, update the notification:
                        if (mNotificationBuilder != null && currentSource.equals(source)) {
                            LogHelper.d(TAG, "getBitmapFromURLAsync: set bitmap to ", source);
                            mNotificationBuilder.setLargeIcon(bitmap);
                            mNotificationManager.notify(NOTIFICATION_ID,
                                    mNotificationBuilder.build());
                        }
                    }
                } catch (IOException e) {
                    LogHelper.e(TAG, e, "getBitmapFromURLAsync: " + source);
                }
                return null;
            }
        }.execute();
    }

}
