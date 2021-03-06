/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.creationgroundmedia.watch;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.Time;
import android.util.Log;
import android.view.SurfaceHolder;

import com.example.android.sunshine.app.R;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.PendingResult;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;

import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Created by George Cohn on 4/20/16.
 * Initial code generated by Android Studio 2.1
 * Modified to display weather information from the Sunshine App
 */

/**
 * Analog watch face with a ticking second hand. In ambient mode, the second hand isn't shown. On
 * devices with low-bit ambient mode, the hands are drawn without anti-aliasing in ambient mode.
 */
public class WatchFace extends CanvasWatchFaceService {
    /**
     * Update rate in milliseconds for interactive mode. We update once a second to advance the
     * second hand.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);
    private static final String DATE_KEY = "DateTime";

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;
    private Context mContext;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<WatchFace.Engine> mWeakReference;

        public EngineHandler(WatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            WatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine
            implements
            DataApi.DataListener,
            GoogleApiClient.ConnectionCallbacks,
            GoogleApiClient.OnConnectionFailedListener {

        // Used in onDataChanged()
        // Must match content provider contract WeatherContract
        // because that's what the sender (SyncAdapter) uses
        public static final String WEATHER_ID = "weather_id";
        public static final String MIN = "min";
        public static final String MAX = "max";

        private final String LOG_TAG = this.getClass().getSimpleName();
        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        boolean haveWxData = false;
        Paint mBackgroundPaint;
        Paint mHandPaint;
        Paint mHighPaint;
        Paint mLowPaint;
        private Bitmap mWxArtBitmap;
        boolean mAmbient;
        Time mTime;
        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mTime.clear(intent.getStringExtra("time-zone"));
                mTime.setToNow();
            }
        };
        int mTapCount;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;
        private GoogleApiClient mGoogleApiClient;
        private String mWxLow;
        private String mWxHigh;
        private LinearGradient mGradient;
        private int mDarkBg;
        private int mLightBg;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(WatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_SHORT)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .build());

            Resources resources = WatchFace.this.getResources();

            mContext = getApplicationContext();
            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(getColor(R.color.background));

            mHandPaint = new Paint();
            mHandPaint.setColor(getColor(R.color.analog_hands));
            mHandPaint.setStrokeWidth(resources.getDimension(R.dimen.analog_hand_stroke));
            mHandPaint.setAntiAlias(true);
            mHandPaint.setStrokeCap(Paint.Cap.ROUND);

            mHighPaint = new Paint();
            mHighPaint.setColor(getColor(R.color.high_text));
            mHighPaint.setTextSize(60);
            mHighPaint.setTextAlign(Paint.Align.CENTER);
            mHighPaint.setAntiAlias(true);

            mLowPaint = new Paint();
            mLowPaint.setColor(getColor(R.color.low_text));
            mLowPaint.setTextSize(40);
            mLowPaint.setTextAlign(Paint.Align.CENTER);
            mLowPaint.setAntiAlias(true);

            setWxArtBitmap(800);

            mTime = new Time();

            mGoogleApiClient = new GoogleApiClient.Builder(mContext)
                    .addApi(Wearable.API)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .build();

            Log.d(LOG_TAG, "mGoogleApiClient: " + mGoogleApiClient);
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mHandPaint.setAntiAlias(!inAmbientMode);
                    mHighPaint.setAntiAlias(!inAmbientMode);
                    mLowPaint.setAntiAlias(!inAmbientMode);
                    mLowPaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        /**
         * Captures tap event (and tap type) and toggles the background color if the user finishes
         * a tap.
         */
        @Override
        public void onTapCommand(int tapType, int x, int y, long eventTime) {
            Resources resources = WatchFace.this.getResources();
            switch (tapType) {
                case TAP_TYPE_TOUCH:
                    // The user has started touching the screen.
                    break;
                case TAP_TYPE_TOUCH_CANCEL:
                    // The user has started a different gesture or otherwise cancelled the tap.
                    break;
                case TAP_TYPE_TAP:
                    // The user has completed the tap gesture.
                    // Does nothing once the watch connects with the app,
                    // because the background becomes a gradient
                    // with colors based on the weather condition.
                    mTapCount++;
                    mBackgroundPaint.setColor(getColor(mTapCount % 2 == 0 ?
                            R.color.background : R.color.background2));
                    break;
            }
            invalidate();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            mTime.setToNow();

            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                int width = canvas.getWidth();
                int height = canvas.getHeight();
                // draw background gradient
                mGradient = new LinearGradient(width, 0, width, height,
                        mDarkBg, mLightBg, Shader.TileMode.CLAMP);
                mBackgroundPaint.setDither(true);
                mBackgroundPaint.setShader(mGradient);
                canvas.drawRect(0, 0, width, height, mBackgroundPaint);
            }

            // Find the center. Ignore the window insets so that, on round watches with a
            // "chin", the watch face is centered on the entire screen, not just the usable
            // portion.
            float centerX = bounds.width() / 2f;
            float centerY = bounds.height() / 2f;

            float secRot = mTime.second / 30f * (float) Math.PI;
            int minutes = mTime.minute;
            float minRot = minutes / 30f * (float) Math.PI;
            float hrRot = ((mTime.hour + (minutes / 60f)) / 6f) * (float) Math.PI;

            float secLength = centerX - 20;
            float minLength = centerX - 40;
            float hrLength = centerX - 80;

            if (!mAmbient) {
                if (haveWxData) {
                    Bitmap wxArtBitmap;
                    int wxArtSize = mWxArtBitmap.getHeight();
                    int minBounds = (int) Math.min(centerX, centerY);
                    wxArtBitmap = wxArtSize > minBounds ?
                            Bitmap.createScaledBitmap(mWxArtBitmap, minBounds, minBounds, false)
                            : mWxArtBitmap;
                    canvas.drawBitmap(wxArtBitmap, centerX - wxArtSize / 2, centerY - wxArtSize, null);

                    float highOriginX = centerX;
                    float highOriginY = centerY * 1.5F;
                    canvas.drawText(mWxHigh, highOriginX, highOriginY, mHighPaint);

                    float lowOriginX = centerX;
                    float lowOriginY = centerY * 1.75F;
                    canvas.drawText(mWxLow, lowOriginX, lowOriginY, mLowPaint);
                }

                float secX = (float) Math.sin(secRot) * secLength;
                float secY = (float) -Math.cos(secRot) * secLength;
                canvas.drawLine(centerX, centerY, centerX + secX, centerY + secY, mHandPaint);
            }

            float minX = (float) Math.sin(minRot) * minLength;
            float minY = (float) -Math.cos(minRot) * minLength;
            canvas.drawLine(centerX, centerY, centerX + minX, centerY + minY, mHandPaint);

            float hrX = (float) Math.sin(hrRot) * hrLength;
            float hrY = (float) -Math.cos(hrRot) * hrLength;
            canvas.drawLine(centerX, centerY, centerX + hrX, centerY + hrY, mHandPaint);
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();
                mGoogleApiClient.connect();

                // Update time zone in case it changed while we weren't visible.
                mTime.clear(TimeZone.getDefault().getID());
                mTime.setToNow();
            } else {
                unregisterReceiver();
                if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
                    Wearable.DataApi.removeListener(mGoogleApiClient, this);
                    mGoogleApiClient.disconnect();
                }
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        @Override
        public void onConnected(@Nullable Bundle bundle) {
            Wearable.DataApi.addListener(mGoogleApiClient, this);
            if (!haveWxData) {
                sendBump();
            }
            Log.d(LOG_TAG, "onConnected: " + bundle);
        }

        @Override
        public void onConnectionSuspended(int i) {
            Log.d(LOG_TAG, "onConnectionSuspended: " + i);
        }

        @Override
        public void onDataChanged(DataEventBuffer dataEventBuffer) {
            Log.d(LOG_TAG, "onDataChanged: " + dataEventBuffer);
            for (DataEvent dataEvent : dataEventBuffer) {
                if (dataEvent.getType() == DataEvent.TYPE_CHANGED) {
                    DataItem item = dataEvent.getDataItem();
                    Log.d(LOG_TAG, "onDataChanged URI " + item.getUri().getPath());
                    if (item.getUri().getPath().compareTo("/wx") == 0) {
                        DataMap dataMap = DataMapItem.fromDataItem(item).getDataMap();
                        setWxArtBitmap(dataMap.getInt(WEATHER_ID));
                        setWxLow(dataMap.getString(MIN));
                        setWxHigh(dataMap.getString(MAX));
                        Log.d(LOG_TAG,
                                "onDataChanged data (low "
                                + mWxLow
                                + ", high "
                                + mWxHigh
                                + "')");
                        haveWxData = true;
                        invalidate();
                    }
                }
            }
        }

        @Override
        public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
            Log.d(LOG_TAG, "onConnectionFailed: " + connectionResult);
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            WatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            WatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }

        /**
         * Normally the app pushes data to us,
         * but on startup we need to prompt it to get it to send us our initial data.
         * We just throw a timestamp at it, otherwise it will never see an onDataChanged event
         */
        private void sendBump() {
            Calendar c = Calendar.getInstance();
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            String strDate = sdf.format(c.getTime());
            PutDataMapRequest putDataMapReq = PutDataMapRequest.create("/watch_bump");
            putDataMapReq.getDataMap().putString(DATE_KEY, strDate);
            PutDataRequest putDataReq = putDataMapReq.asPutDataRequest()
                    .setUrgent();
            PendingResult<DataApi.DataItemResult> pendingResult =
                    Wearable.DataApi.putDataItem(mGoogleApiClient, putDataReq);
            Log.d(LOG_TAG, "sendBump pendingResult: " + pendingResult);
        }

        /**
         * Helper method to provide the art resource id according to the weather condition id returned
         * by the OpenWeatherMap call.
         * @param weatherId from OpenWeatherMap API response
         * @return resource id for the corresponding icon. -1 if no relation is found.
         * Copied from app
         */
        private int getArtResourceForWeatherCondition(int weatherId) {
            // Based on weather code data found at:
            // http://bugs.openweathermap.org/projects/api/wiki/Weather_Condition_Codes
            if (weatherId >= 200 && weatherId <= 232) {
                return R.drawable.art_storm;
            } else if (weatherId >= 300 && weatherId <= 321) {
                return R.drawable.art_light_rain;
            } else if (weatherId >= 500 && weatherId <= 504) {
                return R.drawable.art_rain;
            } else if (weatherId == 511) {
                return R.drawable.art_snow;
            } else if (weatherId >= 520 && weatherId <= 531) {
                return R.drawable.art_rain;
            } else if (weatherId >= 600 && weatherId <= 622) {
                return R.drawable.art_snow;
            } else if (weatherId >= 701 && weatherId <= 761) {
                return R.drawable.art_fog;
            } else if (weatherId == 761 || weatherId == 781) {
                return R.drawable.art_storm;
            } else if (weatherId == 800) {
                return R.drawable.art_clear;
            } else if (weatherId == 801) {
                return R.drawable.art_light_clouds;
            } else if (weatherId >= 802 && weatherId <= 804) {
                return R.drawable.art_clouds;
            }
            return -1;
        }

        /**
         * Patterned after @getArtResourceForWeatherCondition above
         * @param weatherId from OpenWeatherMap API response
         * @param dark boolean to select dark or light version of the color.
         *             Both are used to make the background gradient.
         * @return color for the corresponding weather condition. -1 if no relation is found.
         */
        private int getBgForWeatherCondition(int weatherId, boolean dark) {
            // Based on weather code data found at:
            // http://bugs.openweathermap.org/projects/api/wiki/Weather_Condition_Codes
            if (weatherId >= 200 && weatherId <= 232) {
                // storm
                return dark? getColor(R.color.dark_storm) : getColor(R.color.light_storm);
            } else if (weatherId >= 300 && weatherId <= 321) {
                // light rain
                return dark? getColor(R.color.dark_sky_blue) : getColor(R.color.light_sky_blue);
            } else if (weatherId >= 500 && weatherId <= 504) {
                // rain
                return dark? getColor(R.color.dark_gray) : getColor(R.color.light_gray);
            } else if (weatherId == 511) {
                // snow
                return dark? getColor(R.color.dark_gray) : getColor(R.color.light_gray);
            } else if (weatherId >= 520 && weatherId <= 531) {
                // rain
                return dark? getColor(R.color.dark_gray) : getColor(R.color.light_gray);
            } else if (weatherId >= 600 && weatherId <= 622) {
                // snow
                return dark? getColor(R.color.dark_gray) : getColor(R.color.light_gray);
            } else if (weatherId >= 701 && weatherId <= 761) {
                // fog
                return dark? getColor(R.color.dark_gray) : getColor(R.color.light_gray);
            } else if (weatherId == 761 || weatherId == 781) {
                // storm
                return dark? getColor(R.color.dark_storm) : getColor(R.color.light_storm);
            } else if (weatherId == 800) {
                // clear
                return dark? getColor(R.color.dark_sky_blue) : getColor(R.color.light_sky_blue);
            } else if (weatherId == 801) {
                // light clouds
                return dark? getColor(R.color.dark_sky_blue) : getColor(R.color.light_sky_blue);
            } else if (weatherId >= 802 && weatherId <= 804) {
                // clouds
                return dark? getColor(R.color.dark_gray) : getColor(R.color.light_gray);
            }
            return -1;
        }

        public void setWxArtBitmap(int weatherId) {
            mWxArtBitmap = BitmapFactory.decodeResource(mContext.getResources(),
                    getArtResourceForWeatherCondition(weatherId));
            mDarkBg = getBgForWeatherCondition(weatherId, true);
            mLightBg = getBgForWeatherCondition(weatherId, false);
        }

        public void setWxLow(String wxLow) {
            this.mWxLow = wxLow;
        }

        public void setWxHigh(String mWxHigh) {
            this.mWxHigh = mWxHigh;
        }
    }
}
