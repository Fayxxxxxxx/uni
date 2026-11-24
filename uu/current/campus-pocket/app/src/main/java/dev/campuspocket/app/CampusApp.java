package dev.campuspocket.app;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageManager;
import android.os.Build;

final class CampusApp {
    static final String CURRENT = "cn.unipus.cloud";
    static final String LEGACY = "cn.unipus.ucampus.student";
    static final String DOWNLOAD = "https://app.unipus.cn/u/apd-app.html";
    static final String EXTRA_PACKAGE = "campus_package";

    private CampusApp() {}

    static boolean supports(CharSequence name) {
        return name != null && (CURRENT.contentEquals(name) || LEGACY.contentEquals(name));
    }

    static String preferred(Context context, String lastUsed) {
        if (supports(lastUsed) && installed(context, lastUsed)) return lastUsed;
        if (installed(context, CURRENT)) return CURRENT;
        if (installed(context, LEGACY)) return LEGACY;
        return null;
    }

    static boolean installed(Context context, String name) {
        if (!supports(name)) return false;
        try {
            context.getPackageManager().getApplicationInfo(name, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    static String label(String name) {
        return LEGACY.equals(name) ? "U校园（旧版）" : "U校园";
    }

    static boolean launch(Context context, String name) {
        if (!supports(name)) return false;
        PackageManager manager = context.getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setPackage(name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            context.startActivity(launcher);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            // Some distributions declare a front door without a launcher category.
        }
        try {
            Intent frontDoor = manager.getLaunchIntentForPackage(name);
            if (frontDoor != null) {
                frontDoor.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                context.startActivity(frontDoor);
                return true;
            }
        } catch (ActivityNotFoundException | SecurityException e) {
            // Try the newer Android package launcher below.
        }
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                manager.getLaunchIntentSenderForPackage(name).sendIntent(context, 0, null, null, null);
                return true;
            } catch (IntentSender.SendIntentException | SecurityException e) {
                return false;
            }
        }
        return false;
    }
}
