package io.github.jahn2007.fourma;

import android.app.Application;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

public class FourMaApp extends Application implements XposedServiceHelper.OnServiceListener {
    private final Set<XposedServiceHelper.OnServiceListener> listeners =
            new CopyOnWriteArraySet<>();
    private volatile XposedService service;

    @Override
    public void onCreate() {
        super.onCreate();
        XposedServiceHelper.registerListener(this);
    }

    public void addListener(XposedServiceHelper.OnServiceListener listener, boolean notifyNow) {
        listeners.add(listener);
        XposedService current = service;
        if (notifyNow && current != null) listener.onServiceBind(current);
    }

    public void removeListener(XposedServiceHelper.OnServiceListener listener) {
        listeners.remove(listener);
    }

    @Override
    public void onServiceBind(XposedService service) {
        this.service = service;
        for (XposedServiceHelper.OnServiceListener listener : listeners) {
            listener.onServiceBind(service);
        }
    }

    @Override
    public void onServiceDied(XposedService service) {
        this.service = null;
        for (XposedServiceHelper.OnServiceListener listener : listeners) {
            listener.onServiceDied(service);
        }
    }
}
