/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.view;

/**
 * Provides low-level communication with the system window manager for
 * operations that are bound to a particular context, display or parent window.
 * Instances of this object are sensitive to the compatibility info associated
 * with the running application.
 *
 * This object implements the {@link ViewManager} interface,
 * allowing you to add any View subclass as a top-level window on the screen.
 * Additional window manager specific layout parameters are defined for
 * control over how windows are displayed.  It also implements the {@link WindowManager}
 * interface, allowing you to control the displays attached to the device.
 * 
 * <p>Applications will not normally use WindowManager directly, instead relying
 * on the higher-level facilities in {@link android.app.Activity} and
 * {@link android.app.Dialog}.
 * 
 * <p>Even for low-level window manager access, it is almost never correct to use
 * this class.  For example, {@link android.app.Activity#getWindowManager}
 * provides a window manager for adding windows that are associated with that
 * activity -- the window manager will not normally allow you to add arbitrary
 * windows that are not associated with an activity.
 *
 * @see WindowManager
 * @see WindowManagerGlobal
 * @hide
 */
 /* wwxx ViewManagerµÄÊµÏÖÀàÊÇWindowManagerImpl£¬ËùÒÔµ÷ÓÃµÄÊÇWindowManagerImplµÄaddView·½·¨£¬
 WindowManagerImpl½ô½Ó¾Í°ÑÕâ¸ö»î½»¸øÁËWindowManagerGlobal£¬
 ËùÒÔÒªÈ¥¿´WindowManagerGlobalµÄaddView·½·¨¡£WindowManagerGlobalÊÇÓÃÀ´¹ÜÀíWindowµÄÈ«¾ÖÀà£¬
 ËüÀïÃæÎ¬»¤ÁË¼¸¸öÈ«¾ÖµÄÁÐ±í¡£
 
 */
public final class WindowManagerImpl implements WindowManager {
    private final WindowManagerGlobal mGlobal = WindowManagerGlobal.getInstance();
    private final Display mDisplay;
    private final Window mParentWindow;

    public WindowManagerImpl(Display display) {
        this(display, null);
    }

    private WindowManagerImpl(Display display, Window parentWindow) {
        mDisplay = display;
        mParentWindow = parentWindow;
    }

    public WindowManagerImpl createLocalWindowManager(Window parentWindow) {
        return new WindowManagerImpl(mDisplay, parentWindow);
    }

    public WindowManagerImpl createPresentationWindowManager(Display display) {
        return new WindowManagerImpl(display, mParentWindow);
    }
    /*wwxx wms study part2 11、
    从前面Android应用程序窗口（Activity）的窗口对象（Window）的创建过程分析一文可以知道， WindowManagerImpl 类的成员变量 mGlobal 指向的是一个 WindowManagerGlobal 对象，
    WindowManagerImpl 类的成员函数addView接下来调用 WindowManagerGlobal 类的成员函数addView来给参数view所描述的一个应用程序窗口视图对象关联一个ViewRoot对象。    

    */
    @Override
    public void addView(View view, ViewGroup.LayoutParams params) {
        mGlobal.addView(view, params, mDisplay, mParentWindow);
    }

    @Override
    public void updateViewLayout(View view, ViewGroup.LayoutParams params) {
        mGlobal.updateViewLayout(view, params);
    }

    @Override
    public void removeView(View view) {
        mGlobal.removeView(view, false);
    }

    @Override
    public void removeViewImmediate(View view) {
        mGlobal.removeView(view, true);
    }

    @Override
    public Display getDefaultDisplay() {
        return mDisplay;
    }
}
