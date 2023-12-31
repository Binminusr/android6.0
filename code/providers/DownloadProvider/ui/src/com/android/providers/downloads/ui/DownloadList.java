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

package com.android.providers.downloads.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.DocumentsContract;

import com.android.providers.downloads.Constants;

public class DownloadList extends Activity {
    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        // Trampoline over to new management UI
        final Intent intent = new Intent(DocumentsContract.ACTION_MANAGE_ROOT);
        /* Add for Bug:495369 Only start one activity 2015.11.10 Start */
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK);
        /* Add for Bug:495369 Only start one activity 2015.11.10 End */
        intent.setData(DocumentsContract.buildRootUri(
                Constants.STORAGE_AUTHORITY, Constants.STORAGE_ROOT_ID));
        startActivity(intent);
        finish();
    }
}
