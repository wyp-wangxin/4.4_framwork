/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.server.wm;

import android.view.IWindowId;
import com.android.internal.view.IInputContext;
import com.android.internal.view.IInputMethodClient;
import com.android.internal.view.IInputMethodManager;
import com.android.server.wm.WindowManagerService.H;

import android.content.ClipData;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.graphics.Region;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.util.Slog;
import android.view.Display;
import android.view.IWindow;
import android.view.IWindowSession;
import android.view.InputChannel;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.SurfaceSession;
import android.view.WindowManager;

import java.io.PrintWriter;

/**
 * This class represents an active client session.  There is generally one
 * Session object per process that is interacting with the window manager.
 */
/*wwxx wms study part5

 Session 类的描述同样可以参考前面Android应用程序窗口（Activity）实现框架简要介绍和学习计划一文，这里我们主要描述一下它的作用。
 Session类实现了IWindowSession接口，因此，应用程序进程就可以通过保存在 ViewRootimpl 类的静态成员变量 mWindowSession 
 所描述的一个Session代理对象所实现的IWindowSession接口来与WindowManagerService服务通信，例如：

 1. 在Activity组件的启动过程中，调用这个IWindowSession接口的成员函数add可以将一个关联的W对象传递到WindowManagerService服务，
    以便WindowManagerService服务可以为该Activity组件创建一个WindowState对象。

 2. 在Activity组件的销毁过程中，调用这个这个IWindowSession接口的成员函数remove来请求WindowManagerService服务之前为该Activity组件所创建的一个WindowState对象，
    这一点可以参考前面Android应用程序键盘（Keyboard）消息处理机制分析一文的键盘消息接收通道注销过程分析。

 3. 在Activity组件的运行过程中，调用这个这个IWindowSession接口的成员函数relayout来请求WindowManagerService服务来对该Activity组件的UI进行布局，以便该Activity组件的UI可以正确地显示在屏幕中。



*/

final class Session extends IWindowSession.Stub
        implements IBinder.DeathRecipient {
    final WindowManagerService mService;
    final IInputMethodClient mClient;
    final IInputContext mInputContext;
    final int mUid;
    final int mPid;
    final String mStringName;
    SurfaceSession mSurfaceSession;
    int mNumWindow = 0;
    boolean mClientDead = false;
    /*
    Session类有两个成员变量 mClient 和 mInputContext ，分别用来保存在从应用程序进程传递过来的一个输入法客户端对象和一个输入法上下文对象，它们是在Session类的构造函数中初始化的。

    Session类的构造函数还会检查WindowManagerService服务是否需要获得系统中的输入法管理器服务，即检查WindowManagerService类的成员变量 mHaveInputMethods 的值是否等于true。
    如果这个值等于true，并且WindowManagerService服务还没有获得系统中的输入法管理器服务，即WindowManagerService类的成员变量 mInputMethodManager 的值等于null，
    那么Session类的构造函数就会首先获得这个输入法管理器服务，并且保存在WindowManagerService类的成员变量 mInputMethodManager 中。

    获得了系统中的输入法管理器服务之后，Session类的构造函数就可以调用它的成员函数 addClient 来为正在请求与WindowManagerService服务建立连接的应用程序进程增加它所使用的输入法客户端对象和输入法上下文对象了。

    至此，我们就分析完成一个Session对象的创建过程了，通过这个过程我们就可以知道，每一个应用程序进程在WindowManagerService服务内部都有一个类型为Session的Binder本地对象，
    用来它与WindowManagerService服务之间的连接，而有了这个连接之后，WindowManagerService服务就可以请求应用进程配合管理系统中的应用程序窗口了。


    */
    public Session(WindowManagerService service, IInputMethodClient client,
            IInputContext inputContext) {
        mService = service;
        mClient = client;
        mInputContext = inputContext;
        mUid = Binder.getCallingUid();
        mPid = Binder.getCallingPid();
        StringBuilder sb = new StringBuilder();
        sb.append("Session{");
        sb.append(Integer.toHexString(System.identityHashCode(this)));
        sb.append(" ");
        sb.append(mPid);
        if (mUid < Process.FIRST_APPLICATION_UID) {
            sb.append(":");
            sb.append(mUid);
        } else {
            sb.append(":u");
            sb.append(UserHandle.getUserId(mUid));
            sb.append('a');
            sb.append(UserHandle.getAppId(mUid));
        }
        sb.append("}");
        mStringName = sb.toString();

        synchronized (mService.mWindowMap) {
            if (mService.mInputMethodManager == null && mService.mHaveInputMethods) {
                IBinder b = ServiceManager.getService(
                        Context.INPUT_METHOD_SERVICE);
                mService.mInputMethodManager = IInputMethodManager.Stub.asInterface(b);
            }
        }
        long ident = Binder.clearCallingIdentity();
        try {
            // Note: it is safe to call in to the input method manager
            // here because we are not holding our lock.
            if (mService.mInputMethodManager != null) {
                mService.mInputMethodManager.addClient(client, inputContext,
                        mUid, mPid);
            } else {
                client.setUsingInputMethod(false);
            }
            client.asBinder().linkToDeath(this, 0);
        } catch (RemoteException e) {
            // The caller has died, so we can just forget about this.
            try {
                if (mService.mInputMethodManager != null) {
                    mService.mInputMethodManager.removeClient(client);
                }
            } catch (RemoteException ee) {
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public boolean onTransact(int code, Parcel data, Parcel reply, int flags)
            throws RemoteException {
        try {
            return super.onTransact(code, data, reply, flags);
        } catch (RuntimeException e) {
            // Log all 'real' exceptions thrown to the caller
            if (!(e instanceof SecurityException)) {
                Slog.wtf(WindowManagerService.TAG, "Window Session Crash", e);
            }
            throw e;
        }
    }

    public void binderDied() {
        // Note: it is safe to call in to the input method manager
        // here because we are not holding our lock.
        try {
            if (mService.mInputMethodManager != null) {
                mService.mInputMethodManager.removeClient(mClient);
            }
        } catch (RemoteException e) {
        }
        synchronized(mService.mWindowMap) {
            mClient.asBinder().unlinkToDeath(this, 0);
            mClientDead = true;
            killSessionLocked();
        }
    }

    @Override
    public int add(IWindow window, int seq, WindowManager.LayoutParams attrs,
            int viewVisibility, Rect outContentInsets, InputChannel outInputChannel) {
        return addToDisplay(window, seq, attrs, viewVisibility, Display.DEFAULT_DISPLAY,
                outContentInsets, outInputChannel);
    }
    /*wwxx wms study part5 三.2

    Session类的成员函数addToDisplay的实现很简单，它只是调用了外部类WindowManagerService的成员函数addWindow来进一步为正在启动的Activity组件创建一个WindowState对象。

    */
    @Override
    public int addToDisplay(IWindow window, int seq, WindowManager.LayoutParams attrs,
            int viewVisibility, int displayId, Rect outContentInsets,
            InputChannel outInputChannel) {
        return mService.addWindow(this, window, seq, attrs, viewVisibility, displayId,
                outContentInsets, outInputChannel);
    }

    @Override
    public int addWithoutInputChannel(IWindow window, int seq, WindowManager.LayoutParams attrs,
            int viewVisibility, Rect outContentInsets) {
        return addToDisplayWithoutInputChannel(window, seq, attrs, viewVisibility,
                Display.DEFAULT_DISPLAY, outContentInsets);
    }

    @Override
    public int addToDisplayWithoutInputChannel(IWindow window, int seq, WindowManager.LayoutParams attrs,
            int viewVisibility, int displayId, Rect outContentInsets) {
        return mService.addWindow(this, window, seq, attrs, viewVisibility, displayId,
            outContentInsets, null);
    }

    public void remove(IWindow window) {
        mService.removeWindow(this, window);
    }
    /*wwxx 
    Session类的成员函数relayout的实现很简单，它只是调用了WindowManagerService类的成员函数relayoutWindow来进一步计算参数window所描述的一个Activity窗品的大小，
    接下来我们就继续分析WindowManagerService类的成员函数relayoutWindow的实现。去看看。
    */
    public int relayout(IWindow window, int seq, WindowManager.LayoutParams attrs,
            int requestedWidth, int requestedHeight, int viewFlags,
            int flags, Rect outFrame, Rect outOverscanInsets, Rect outContentInsets,
            Rect outVisibleInsets, Configuration outConfig, Surface outSurface) {
        if (false) Slog.d(WindowManagerService.TAG, ">>>>>> ENTERED relayout from "
                + Binder.getCallingPid());
        int res = mService.relayoutWindow(this, window, seq, attrs,
                requestedWidth, requestedHeight, viewFlags, flags,
                outFrame, outOverscanInsets, outContentInsets, outVisibleInsets,
                outConfig, outSurface);
        if (false) Slog.d(WindowManagerService.TAG, "<<<<<< EXITING relayout to "
                + Binder.getCallingPid());
        return res;
    }

    public void performDeferredDestroy(IWindow window) {
        mService.performDeferredDestroyWindow(this, window);
    }

    public boolean outOfMemory(IWindow window) {
        return mService.outOfMemoryWindow(this, window);
    }

    public void setTransparentRegion(IWindow window, Region region) {
        mService.setTransparentRegionWindow(this, window, region);
    }

    public void setInsets(IWindow window, int touchableInsets,
            Rect contentInsets, Rect visibleInsets, Region touchableArea) {
        mService.setInsetsWindow(this, window, touchableInsets, contentInsets,
                visibleInsets, touchableArea);
    }

    public void getDisplayFrame(IWindow window, Rect outDisplayFrame) {
        mService.getWindowDisplayFrame(this, window, outDisplayFrame);
    }

    public void finishDrawing(IWindow window) {
        if (WindowManagerService.localLOGV) Slog.v(
            WindowManagerService.TAG, "IWindow finishDrawing called for " + window);
        mService.finishDrawingWindow(this, window);
    }

    public void setInTouchMode(boolean mode) {
        synchronized(mService.mWindowMap) {
            mService.mInTouchMode = mode;
        }
    }

    public boolean getInTouchMode() {
        synchronized(mService.mWindowMap) {
            return mService.mInTouchMode;
        }
    }

    public boolean performHapticFeedback(IWindow window, int effectId,
            boolean always) {
        synchronized(mService.mWindowMap) {
            long ident = Binder.clearCallingIdentity();
            try {
                return mService.mPolicy.performHapticFeedbackLw(
                        mService.windowForClientLocked(this, window, true),
                        effectId, always);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    /* Drag/drop */
    public IBinder prepareDrag(IWindow window, int flags,
            int width, int height, Surface outSurface) {
        return mService.prepareDragSurface(window, mSurfaceSession, flags,
                width, height, outSurface);
    }

    public boolean performDrag(IWindow window, IBinder dragToken,
            float touchX, float touchY, float thumbCenterX, float thumbCenterY,
            ClipData data) {
        if (WindowManagerService.DEBUG_DRAG) {
            Slog.d(WindowManagerService.TAG, "perform drag: win=" + window + " data=" + data);
        }

        synchronized (mService.mWindowMap) {
            if (mService.mDragState == null) {
                Slog.w(WindowManagerService.TAG, "No drag prepared");
                throw new IllegalStateException("performDrag() without prepareDrag()");
            }

            if (dragToken != mService.mDragState.mToken) {
                Slog.w(WindowManagerService.TAG, "Performing mismatched drag");
                throw new IllegalStateException("performDrag() does not match prepareDrag()");
            }

            WindowState callingWin = mService.windowForClientLocked(null, window, false);
            if (callingWin == null) {
                Slog.w(WindowManagerService.TAG, "Bad requesting window " + window);
                return false;  // !!! TODO: throw here?
            }

            // !!! TODO: if input is not still focused on the initiating window, fail
            // the drag initiation (e.g. an alarm window popped up just as the application
            // called performDrag()

            mService.mH.removeMessages(H.DRAG_START_TIMEOUT, window.asBinder());

            // !!! TODO: extract the current touch (x, y) in screen coordinates.  That
            // will let us eliminate the (touchX,touchY) parameters from the API.

            // !!! FIXME: put all this heavy stuff onto the mH looper, as well as
            // the actual drag event dispatch stuff in the dragstate

            Display display = callingWin.mDisplayContent.getDisplay();
            mService.mDragState.register(display);
            mService.mInputMonitor.updateInputWindowsLw(true /*force*/);
            if (!mService.mInputManager.transferTouchFocus(callingWin.mInputChannel,
                    mService.mDragState.mServerChannel)) {
                Slog.e(WindowManagerService.TAG, "Unable to transfer touch focus");
                mService.mDragState.unregister();
                mService.mDragState = null;
                mService.mInputMonitor.updateInputWindowsLw(true /*force*/);
                return false;
            }

            mService.mDragState.mData = data;
            mService.mDragState.mCurrentX = touchX;
            mService.mDragState.mCurrentY = touchY;
            mService.mDragState.broadcastDragStartedLw(touchX, touchY);

            // remember the thumb offsets for later
            mService.mDragState.mThumbOffsetX = thumbCenterX;
            mService.mDragState.mThumbOffsetY = thumbCenterY;

            // Make the surface visible at the proper location
            final SurfaceControl surfaceControl = mService.mDragState.mSurfaceControl;
            if (WindowManagerService.SHOW_LIGHT_TRANSACTIONS) Slog.i(
                    WindowManagerService.TAG, ">>> OPEN TRANSACTION performDrag");
            SurfaceControl.openTransaction();
            try {
                surfaceControl.setPosition(touchX - thumbCenterX,
                        touchY - thumbCenterY);
                surfaceControl.setAlpha(.7071f);
                surfaceControl.setLayer(mService.mDragState.getDragLayerLw());
                surfaceControl.setLayerStack(display.getLayerStack());
                surfaceControl.show();
            } finally {
                SurfaceControl.closeTransaction();
                if (WindowManagerService.SHOW_LIGHT_TRANSACTIONS) Slog.i(
                        WindowManagerService.TAG, "<<< CLOSE TRANSACTION performDrag");
            }
        }

        return true;    // success!
    }

    public void reportDropResult(IWindow window, boolean consumed) {
        IBinder token = window.asBinder();
        if (WindowManagerService.DEBUG_DRAG) {
            Slog.d(WindowManagerService.TAG, "Drop result=" + consumed + " reported by " + token);
        }

        synchronized (mService.mWindowMap) {
            long ident = Binder.clearCallingIdentity();
            try {
                if (mService.mDragState == null) {
                    // Most likely the drop recipient ANRed and we ended the drag
                    // out from under it.  Log the issue and move on.
                    Slog.w(WindowManagerService.TAG, "Drop result given but no drag in progress");
                    return;
                }

                if (mService.mDragState.mToken != token) {
                    // We're in a drag, but the wrong window has responded.
                    Slog.w(WindowManagerService.TAG, "Invalid drop-result claim by " + window);
                    throw new IllegalStateException("reportDropResult() by non-recipient");
                }

                // The right window has responded, even if it's no longer around,
                // so be sure to halt the timeout even if the later WindowState
                // lookup fails.
                mService.mH.removeMessages(H.DRAG_END_TIMEOUT, window.asBinder());
                WindowState callingWin = mService.windowForClientLocked(null, window, false);
                if (callingWin == null) {
                    Slog.w(WindowManagerService.TAG, "Bad result-reporting window " + window);
                    return;  // !!! TODO: throw here?
                }

                mService.mDragState.mDragResult = consumed;
                mService.mDragState.endDragLw();
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    public void dragRecipientEntered(IWindow window) {
        if (WindowManagerService.DEBUG_DRAG) {
            Slog.d(WindowManagerService.TAG, "Drag into new candidate view @ " + window.asBinder());
        }
    }

    public void dragRecipientExited(IWindow window) {
        if (WindowManagerService.DEBUG_DRAG) {
            Slog.d(WindowManagerService.TAG, "Drag from old candidate view @ " + window.asBinder());
        }
    }

    public void setWallpaperPosition(IBinder window, float x, float y, float xStep, float yStep) {
        synchronized(mService.mWindowMap) {
            long ident = Binder.clearCallingIdentity();
            try {
                mService.setWindowWallpaperPositionLocked(
                        mService.windowForClientLocked(this, window, true),
                        x, y, xStep, yStep);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    public void wallpaperOffsetsComplete(IBinder window) {
        mService.wallpaperOffsetsComplete(window);
    }

    public Bundle sendWallpaperCommand(IBinder window, String action, int x, int y,
            int z, Bundle extras, boolean sync) {
        synchronized(mService.mWindowMap) {
            long ident = Binder.clearCallingIdentity();
            try {
                return mService.sendWindowWallpaperCommandLocked(
                        mService.windowForClientLocked(this, window, true),
                        action, x, y, z, extras, sync);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    public void wallpaperCommandComplete(IBinder window, Bundle result) {
        mService.wallpaperCommandComplete(window, result);
    }

    public void setUniverseTransform(IBinder window, float alpha, float offx, float offy,
            float dsdx, float dtdx, float dsdy, float dtdy) {
        synchronized(mService.mWindowMap) {
            long ident = Binder.clearCallingIdentity();
            try {
                mService.setUniverseTransformLocked(
                        mService.windowForClientLocked(this, window, true),
                        alpha, offx, offy, dsdx, dtdx, dsdy, dtdy);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    public void onRectangleOnScreenRequested(IBinder token, Rect rectangle, boolean immediate) {
        synchronized(mService.mWindowMap) {
            final long identity = Binder.clearCallingIdentity();
            try {
                mService.onRectangleOnScreenRequested(token, rectangle, immediate);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    public IWindowId getWindowId(IBinder window) {
        return mService.getWindowId(window);
    }
    /*wwxx wms study part5 三.6、
    Session类的成员变量 mSurfaceSession 指向的是一个 SurfaceSession 对象，
    这个SurfaceSession对象是WindowManagerService服务用来与SurfaceSession服务建立连接的。
    Session类的成员函数 windowAddedLocked 首先检查这个成员变量的值是否等于null。如果等于null的话，
    那么就说明WindowManagerService服务尚未为当前正在请求增加窗口的应用程序进程创建一个用来连接SurfaceSession服务的SurfaceSession对象，
    因此，Session类的成员函数 windowAddedLocked 就会创建一个 SurfaceSession 对象，并且保存在成员变量mSurfaceSession中，
    并且将正在处理的Session对象添加WindowManagerService类的成员变量 mSession 所描述的一个HashSet中去，
    表示WindowManagerService服务又多了一个激活的应用程序进程连接。

    Session 类的另外一个成员变量 mNumWindow 是一个整型变量，用来描述当前正在处理的Session对象内部包含有多少个窗口，
    即运行在引用了当前正在处理的Session对象的应用程序进程的内部的窗口的数量。
    每当运行在应用程序进程中的窗口销毁时，该应用程序进程就会通知WindowManagerService服务移除用来描述该窗口状态的一个WindowState对象，
    并且通知它所引用的Session对象减少其成员变量mNumWindow的值。当一个Session对象的成员变量mNumWindow的值减少为0时，
    就说明该Session对象所描述的应用程序进程连接已经不需要了，
    因此，该Session对象就可以杀掉其成员变量 mSurfaceSession 所描述的一个SurfaceSession对象，以便可以断开和SurfaceSession服务的连接。

    接下来，我们就继续分析SurfaceSession类的构造函数的实现，以便可以了解一个SurfaceSession对象是如何与SurfaceSession服务建立连接的。
    */
    void windowAddedLocked() {
        if (mSurfaceSession == null) {
            if (WindowManagerService.localLOGV) Slog.v(
                WindowManagerService.TAG, "First window added to " + this + ", creating SurfaceSession");
            mSurfaceSession = new SurfaceSession();// //wwxx ´´½¨´°¿ÚËù¶ÔÓ¦µÄSurfaceSession
            if (WindowManagerService.SHOW_TRANSACTIONS) Slog.i(
                    WindowManagerService.TAG, "  NEW SURFACE SESSION " + mSurfaceSession);
            mService.mSessions.add(this);
        }
        mNumWindow++;
    }

    void windowRemovedLocked() {
        mNumWindow--;
        killSessionLocked();
    }

    void killSessionLocked() {
        if (mNumWindow <= 0 && mClientDead) {
            mService.mSessions.remove(this);
            if (mSurfaceSession != null) {
                if (WindowManagerService.localLOGV) Slog.v(
                    WindowManagerService.TAG, "Last window removed from " + this
                    + ", destroying " + mSurfaceSession);
                if (WindowManagerService.SHOW_TRANSACTIONS) Slog.i(
                        WindowManagerService.TAG, "  KILL SURFACE SESSION " + mSurfaceSession);
                try {
                    mSurfaceSession.kill();
                } catch (Exception e) {
                    Slog.w(WindowManagerService.TAG, "Exception thrown when killing surface session "
                        + mSurfaceSession + " in session " + this
                        + ": " + e.toString());
                }
                mSurfaceSession = null;
            }
        }
    }

    void dump(PrintWriter pw, String prefix) {
        pw.print(prefix); pw.print("mNumWindow="); pw.print(mNumWindow);
                pw.print(" mClientDead="); pw.print(mClientDead);
                pw.print(" mSurfaceSession="); pw.println(mSurfaceSession);
    }

    @Override
    public String toString() {
        return mStringName;
    }
}