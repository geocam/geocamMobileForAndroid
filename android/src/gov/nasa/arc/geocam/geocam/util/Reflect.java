// __BEGIN_LICENSE__
// Copyright (C) 2008-2010 United States Government as represented by
// the Administrator of the National Aeronautics and Space Administration.
// All Rights Reserved.
// __END_LICENSE__

package gov.nasa.arc.geocam.geocam.util;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

import android.app.NotificationManager;
import android.content.Context;
import android.util.Log;

public class Reflect {
    public static final String TAG = "Reflection";

    // Compatibility layer for getting preview sizes for the camera
    public static class CameraParameters {
        private static Method mGetSupportedPreviewSizes;
        static {
            try {
                mGetSupportedPreviewSizes = android.hardware.Camera.Parameters.class.getMethod("getSupportedPreviewSizes", (Class[]) null);
            } catch (NoSuchMethodException e) {
                Log.w(TAG, "getSupportedPreviewSizes not available");
            }
        }

        public static List<android.hardware.Camera.Size> getSupportedPreviewSizes(android.hardware.Camera.Parameters parameters) {
            if (mGetSupportedPreviewSizes == null)
                return null;

            try {
                return (List<android.hardware.Camera.Size>) mGetSupportedPreviewSizes.invoke(parameters, (Object[]) null);
            } catch (IllegalAccessException e) {
                Log.e(TAG, "getSupportedPreviewSizes: we have it, but can't call it");
                return null;
            } catch (InvocationTargetException e) {
                Log.e(TAG, "getSupportedPreviewSizes: threw an exception: " + e);
                return null;
            }
        }
    }

    // Compatability layer between 1.6 and 2.0 for setting a service
    // in the foreground
    public static class Service {
        private static Method mStartForeground;
        private static final Class[] mStartParams = 
            new Class[] { int.class, android.app.Notification.class };

        private static Method mStopForeground;
        private static final Class[] mStopParams = 
            new Class[] { boolean.class };

        static {
            try {
                mStartForeground = android.app.Service.class.getMethod("startForeground", mStartParams);
                mStopForeground = android.app.Service.class.getMethod("stopForeground", mStopParams);
            } catch (NoSuchMethodException e) {
                Log.w(TAG, "startForeground not available");
            }
        }

        public static void startForeground(android.app.Service service, int id, android.app.Notification notification) {
            if (mStartForeground == null) {
                Log.d(TAG, "falling back on old-school setForeground");
                service.setForeground(true);
                NotificationManager notificationManager = (NotificationManager) service.getSystemService(Context.NOTIFICATION_SERVICE);
                notificationManager.notify(id, notification);
                return;
            }

            try {
                Object[] args = new Object[] { Integer.valueOf(id), notification };
                mStartForeground.invoke(service, args);
            } catch (IllegalAccessException e) {
                Log.e(TAG, "startForeground: we have it, but can't call it");
            } catch (InvocationTargetException e) {
                Log.e(TAG, "startForeground: threw an exception: " + e);
            }
        }

        public static void stopForeground(android.app.Service service, int id) {
            if (mStopForeground == null) {
                Log.d(TAG, "falling back on old-school setForeground");
                NotificationManager notificationManager = (NotificationManager) service.getSystemService(Context.NOTIFICATION_SERVICE);
                notificationManager.cancel(id);
                service.setForeground(false);
                return;
            }

            try {
                Object[] args = new Object[] { Boolean.TRUE };
                mStopForeground.invoke(service, args);
            } catch (IllegalAccessException e) {
                Log.e(TAG, "stopForeground: we have it, but can't call it");
            } catch (InvocationTargetException e) {
                Log.e(TAG, "stopForeground: threw an exception: " + e);
            }
        }
    }

}