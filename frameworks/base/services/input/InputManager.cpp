/*
 * Copyright (C) 2010 The Android Open Source Project
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

#define LOG_TAG "InputManager"

//#define LOG_NDEBUG 0

#include "InputManager.h"

#include <cutils/log.h>

namespace android {

InputManager::InputManager(
        const sp<EventHubInterface>& eventHub,
        const sp<InputReaderPolicyInterface>& readerPolicy,
        const sp<InputDispatcherPolicyInterface>& dispatcherPolicy) {
    mDispatcher = new InputDispatcher(dispatcherPolicy);
    mReader = new InputReader(eventHub, readerPolicy, mDispatcher);
    initialize();
}


/*wwxx
InputManager 的构造函数中又创建了两个对象，InputDispatcher 对象和 InputReader 对象。然后调用了 initialize() 函数。
initialize() 函数中创建了两个线程对象，但是没有启动这两个线程，如下所示:

*/
InputManager::InputManager(
        const sp<InputReaderInterface>& reader,
        const sp<InputDispatcherInterface>& dispatcher) :
        mReader(reader),
        mDispatcher(dispatcher) {
    initialize();
}

InputManager::~InputManager() {
    stop();
}

void InputManager::initialize() {
    mReaderThread = new InputReaderThread(mReader);
    mDispatcherThread = new InputDispatcherThread(mDispatcher);
}

status_t InputManager::start() {
    status_t result = mDispatcherThread->run("InputDispatcher", PRIORITY_URGENT_DISPLAY);
    if (result) {
        ALOGE("Could not start InputDispatcher thread due to error %d.", result);
        return result;
    }

    result = mReaderThread->run("InputReader", PRIORITY_URGENT_DISPLAY);//这里，在InputManager 的 start()函数中启动了 initialize() 函数中创建的两个线程对象关联的线程。
    if (result) {
        ALOGE("Could not start InputReader thread due to error %d.", result);

        mDispatcherThread->requestExit();
        return result;
    }

    return OK;
}

status_t InputManager::stop() {
    status_t result = mReaderThread->requestExitAndWait();
    if (result) {
        ALOGW("Could not stop InputReader thread due to error %d.", result);
    }

    result = mDispatcherThread->requestExitAndWait();
    if (result) {
        ALOGW("Could not stop InputDispatcher thread due to error %d.", result);
    }

    return OK;
}

sp<InputReaderInterface> InputManager::getReader() {
    return mReader;
}

sp<InputDispatcherInterface> InputManager::getDispatcher() {
    return mDispatcher;
}

} // namespace android
