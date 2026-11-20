package com.example.intentprobe.scan;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import com.example.intentprobe.model.AppEntry;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Enumerates installed applications and records the APK facts a scan needs. */
public final class AppListLoader {

    private AppListLoader() {}

    public static List<AppEntry> load(Context context) {
        PackageManager pm = context.getPackageManager();
        String self = context.getPackageName();
        List<ApplicationInfo> infos = pm.getInstalledApplications(0);
        List<AppEntry> result = new ArrayList<>(infos.size());
        for (ApplicationInfo info : infos) {
            if (info == null || self.equals(info.packageName)) continue;
            AppEntry entry = new AppEntry();
            entry.pkg = info.packageName;
            try {
                CharSequence label = pm.getApplicationLabel(info);
                entry.label = label == null ? info.packageName : label.toString();
            } catch (Throwable t) {
                entry.label = info.packageName;
            }
            entry.apkPath = info.sourceDir;
            entry.system = (info.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            entry.targetSdk = info.targetSdkVersion;
            try {
                PackageInfo packageInfo = pm.getPackageInfo(info.packageName, 0);
                entry.versionCode = packageInfo.getLongVersionCode();
                entry.lastUpdateTime = packageInfo.lastUpdateTime;
            } catch (PackageManager.NameNotFoundException ignored) {
            }
            if (entry.apkPath != null) {
                entry.apkMtime = new File(entry.apkPath).lastModified();
            }
            result.add(entry);
        }
        result.sort((a, b) -> {
            String left = a.label == null ? a.pkg : a.label;
            String right = b.label == null ? b.pkg : b.label;
            int byLabel = left.compareToIgnoreCase(right);
            return byLabel != 0 ? byLabel : a.pkg.compareToIgnoreCase(b.pkg);
        });
        return result;
    }
}
