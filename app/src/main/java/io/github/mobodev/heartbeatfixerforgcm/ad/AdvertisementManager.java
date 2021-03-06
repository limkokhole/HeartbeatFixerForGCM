package io.github.mobodev.heartbeatfixerforgcm.ad;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.NotificationManagerCompat;
import android.util.Log;
import com.google.android.gms.tasks.OnCanceledListener;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.Task;
import com.google.firebase.analytics.FirebaseAnalytics;
import com.google.firebase.remoteconfig.FirebaseRemoteConfig;
import com.google.gson.Gson;
import io.github.mobodev.heartbeatfixerforgcm.BuildConfig;
import io.github.mobodev.heartbeatfixerforgcm.GlideApp;
import io.github.mobodev.heartbeatfixerforgcm.R;

import java.util.concurrent.ExecutionException;

/**
 * Created by bshao on 5/13/18.
 */

public class AdvertisementManager {
    private static final String TAG = "AdManager";
    private static AdvertisementManager sInstance;

    private Context mContext;
    private SharedPreferences mPreferences;

    private int mLocalAdVersion;
    private FirebaseRemoteConfig mFirebaseRemoteConfig;
    private Gson mGson;
    private boolean mShowedInSession;

    private AdvertisementManager(@NonNull Context context) {
        mContext = context.getApplicationContext();
        mPreferences = mContext.getSharedPreferences("ad", Context.MODE_PRIVATE);

        mLocalAdVersion = mPreferences.getInt("version", 0);

        mFirebaseRemoteConfig = FirebaseRemoteConfig.getInstance();
        mGson = new Gson();
    }

    public static synchronized AdvertisementManager getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new AdvertisementManager(context);
        }
        return sInstance;
    }

    public void showAdIfNeeded() {
        final Advertisement remoteAdvertisement = getRemoteAdvertisement();
        if (remoteAdvertisement.version > mLocalAdVersion && !mShowedInSession) {
            showAd(remoteAdvertisement);
        }
    }

    private Advertisement getRemoteAdvertisement() {
        final String ad = mFirebaseRemoteConfig.getString("ad");
        final Advertisement advertisement = mGson.fromJson(ad, Advertisement.class);

        mFirebaseRemoteConfig.fetch(BuildConfig.DEBUG ? 0 : 3600)
                .addOnCompleteListener(new OnCompleteListener<Void>() {
                    @Override
                    public void onComplete(@NonNull Task<Void> task) {
                        Log.d(TAG, "fetch remote config onComplete, success: " + task.isSuccessful());
                        if (task.isSuccessful()) {
                            mFirebaseRemoteConfig.activateFetched();
                        }
                    }
                })
                .addOnCanceledListener(new OnCanceledListener() {
                    @Override
                    public void onCanceled() {
                        Log.d(TAG, "fetch remote config canceled");
                    }
                })
                .addOnFailureListener(new OnFailureListener() {
                    @Override
                    public void onFailure(@NonNull Exception e) {
                        Log.w(TAG, "fetch remote config error", e);
                    }
                });

        return advertisement;
    }

    private void showAd(final Advertisement advertisement) {
        AsyncTask.execute(new Runnable() {
            @Override
            public void run() {
                Bitmap avatar = null;
                try {
                    avatar = GlideApp.with(mContext)
                            .asBitmap()
                            .load(advertisement.largeIconUrl)
                            .circleCrop()
                            .submit()
                            .get();
                } catch (InterruptedException | ExecutionException e) {
                    Log.w(TAG, "load largeIconUrl error: " + advertisement.largeIconUrl);
                }

                if (avatar == null) {
                    return;
                }

                Bitmap picture = null;
                try {
                    picture = GlideApp.with(mContext)
                            .asBitmap()
                            .load(advertisement.bigPictureUrl)
                            .submit()
                            .get();
                } catch (InterruptedException | ExecutionException e) {
                    Log.w(TAG, "load bigPictureUrl error: " + advertisement.bigPictureUrl);
                }

                if (picture == null) {
                    return;
                }


                final Intent clickIntent = new Intent(AnalyticsReceiver.ACTION_CLICK);
                clickIntent.setClass(mContext, AnalyticsReceiver.class);
                clickIntent.putExtra("ad", advertisement);

                final Intent deleteIntent = new Intent(AnalyticsReceiver.ACTION_DELETE);
                deleteIntent.setClass(mContext, AnalyticsReceiver.class);
                deleteIntent.putExtra("ad", advertisement);

                NotificationCompat.Builder builder = new NotificationCompat.Builder(mContext)
                        .setDefaults(NotificationCompat.DEFAULT_ALL)
                        .setAutoCancel(true)
                        .setContentTitle(advertisement.title)
                        .setContentText(advertisement.message)
                        .setSmallIcon(R.drawable.ic_notification)
                        .setLargeIcon(avatar)
                        .setStyle(new NotificationCompat.BigPictureStyle()
                                .bigPicture(picture))
                        .setContentIntent(
                                PendingIntent.getBroadcast(mContext, 0, clickIntent,
                                        PendingIntent.FLAG_UPDATE_CURRENT))
                        .setDeleteIntent(
                                PendingIntent.getBroadcast(mContext, 0, deleteIntent,
                                        PendingIntent.FLAG_UPDATE_CURRENT));
                NotificationManagerCompat.from(mContext).notify(0, builder.build());

                Bundle bundle = new Bundle();
                bundle.putInt("version", advertisement.version);
                FirebaseAnalytics.getInstance(mContext).logEvent("push_ad_show", bundle);

                mLocalAdVersion = advertisement.version;
                mPreferences.edit().putInt("version", mLocalAdVersion).apply();
                mShowedInSession = true;
            }
        });
    }
}
