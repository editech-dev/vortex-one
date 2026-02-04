package top.niunaijun.blackbox.fake.delegate;

import android.app.IServiceConnection;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import black.android.app.BRIServiceConnectionO;
import top.niunaijun.blackbox.utils.compat.BuildCompat;

/**
 * ServiceConnectionDelegate using raw Binder to support Android 16's
 * IBinderSession.
 * 
 * Android 16 (API 36) added a new abstract method to IServiceConnection.Stub:
 * connected(ComponentName, IBinder, IBinderSession, boolean)
 * 
 * Since IBinderSession doesn't exist in older SDKs, we can't compile against
 * it.
 * Solution: extend Binder directly and implement onTransact to handle all
 * method calls.
 */
public class ServiceConnectionDelegate extends Binder implements IServiceConnection {
    private static final String TAG = "ServiceConnDelegate";
    private static final String DESCRIPTOR = "android.app.IServiceConnection";

    // Transaction codes for IServiceConnection methods
    private static final int TRANSACTION_connected = IBinder.FIRST_CALL_TRANSACTION + 0;

    private static final Map<IBinder, ServiceConnectionDelegate> sServiceConnectDelegate = new HashMap<>();
    private final IServiceConnection mConn;
    private final ComponentName mComponentName;

    private ServiceConnectionDelegate(IServiceConnection mConn, ComponentName targetComponent) {
        this.mConn = mConn;
        this.mComponentName = targetComponent;
        this.attachInterface(this, DESCRIPTOR);
    }

    public static ServiceConnectionDelegate getDelegate(IBinder iBinder) {
        return sServiceConnectDelegate.get(iBinder);
    }

    public static IServiceConnection createProxy(IServiceConnection base, Intent intent) {
        final IBinder iBinder = base.asBinder();
        ServiceConnectionDelegate delegate = sServiceConnectDelegate.get(iBinder);
        if (delegate == null) {
            try {
                iBinder.linkToDeath(new IBinder.DeathRecipient() {
                    @Override
                    public void binderDied() {
                        sServiceConnectDelegate.remove(iBinder);
                        iBinder.unlinkToDeath(this, 0);
                    }
                }, 0);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
            delegate = new ServiceConnectionDelegate(base, intent.getComponent());
            sServiceConnectDelegate.put(iBinder, delegate);
        }
        return delegate;
    }

    @Override
    public IBinder asBinder() {
        return this;
    }

    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        if (code == TRANSACTION_connected) {
            data.enforceInterface(DESCRIPTOR);

            // Read ComponentName
            ComponentName name = null;
            if (data.readInt() != 0) {
                name = ComponentName.CREATOR.createFromParcel(data);
            }

            // Read IBinder service
            IBinder service = data.readStrongBinder();

            // Handle different Android versions by reading remaining data
            boolean dead = false;
            Object session = null;

            if (android.os.Build.VERSION.SDK_INT >= 36) {
                // Android 16: has IBinderSession (as IBinder) and dead flag
                // Read session as IBinder (IBinderSession extends IInterface)
                session = data.readStrongBinder();
                dead = data.readInt() != 0;
            } else if (BuildCompat.isOreo()) {
                // Android 8-15: has dead flag
                dead = data.readInt() != 0;
            }
            // Pre-Oreo: no additional parameters

            // Forward the connection
            doConnected(name, service, session, dead);

            return true;
        }
        return super.onTransact(code, data, reply, flags);
    }

    @Override
    public void connected(ComponentName name, IBinder service) throws RemoteException {
        doConnected(name, service, null, false);
    }

    // Called directly for Android 8-15
    public void connected(ComponentName name, IBinder service, boolean dead) throws RemoteException {
        doConnected(name, service, null, dead);
    }

    /**
     * Internal handler that forwards the connection to the original
     * IServiceConnection
     */
    private void doConnected(ComponentName name, IBinder service, Object session, boolean dead) throws RemoteException {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 36) {
                // Android 16+: Use reflection to call 4-parameter method
                forwardToOriginalV(service, session, dead);
            } else if (BuildCompat.isOreo()) {
                // Android 8-15: Use 3-parameter method
                BRIServiceConnectionO.get(mConn).connected(mComponentName, service, dead);
            } else {
                // Pre-Oreo: Use 2-parameter method
                mConn.connected(name, service);
            }
        } catch (Exception e) {
            Log.e(TAG, "Forward connection failed, trying fallback", e);
            fallbackConnection(service, dead);
        }
    }

    private void fallbackConnection(IBinder service, boolean dead) throws RemoteException {
        try {
            if (BuildCompat.isOreo()) {
                BRIServiceConnectionO.get(mConn).connected(mComponentName, service, dead);
            } else {
                mConn.connected(mComponentName, service);
            }
        } catch (Exception e2) {
            Log.e(TAG, "Fallback also failed", e2);
        }
    }

    /**
     * Forward to original using Android 16's 4-parameter signature via reflection
     */
    private void forwardToOriginalV(IBinder service, Object session, boolean dead) throws Exception {
        Class<?> binderSessionClass = Class.forName("android.app.IBinderSession");

        Method connectedMethod = mConn.getClass().getMethod(
                "connected",
                ComponentName.class,
                IBinder.class,
                binderSessionClass,
                boolean.class);

        connectedMethod.invoke(mConn, mComponentName, service, session, dead);
    }
}
