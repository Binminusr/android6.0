/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.messaging.ui.photoviewer;

import com.android.ex.photo.PhotoViewActivity;
import com.android.ex.photo.PhotoViewController;
import com.android.messaging.util.OsUtil;

/**
 * Activity to display the conversation images in full-screen. Most of the customization is in
 * {@link BuglePhotoViewController}.
 */
public class BuglePhotoViewActivity extends PhotoViewActivity {
    @Override
    public PhotoViewController createController() {
        if(!OsUtil.hasSmsPermission()){
               OsUtil.requestMissingPermission(this);
        }
        BuglePhotoViewController  controller = new BuglePhotoViewController(this);
        controller.setHost(this);
        return controller;
    }
}
