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

package com.android.contacts.list;

import android.content.Intent;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;
//sprd add
import java.util.List;
/**
 * Parsed form of the intent sent to the Contacts application.
 */
public class ContactsRequest {

    /** Default mode: browse contacts */
    public static final int ACTION_DEFAULT = 10;

    /** Show all contacts */
    public static final int ACTION_ALL_CONTACTS = 15;

    /** Show all contacts with phone numbers */
    public static final int ACTION_CONTACTS_WITH_PHONES = 17;

    /** Show contents of a specific group */
    public static final int ACTION_GROUP = 20;

    /** Show all starred contacts */
    public static final int ACTION_STARRED = 30;

    /** Show frequently contacted contacts */
    public static final int ACTION_FREQUENT = 40;

    /** Show starred and the frequent */
    public static final int ACTION_STREQUENT = 50;

    /** Show all contacts and pick them when clicking */
    public static final int ACTION_PICK_CONTACT = 60;

    /** Show all contacts as well as the option to create a new one */
    public static final int ACTION_PICK_OR_CREATE_CONTACT = 70;

    /** Show all contacts and pick them for edit when clicking, and allow creating a new contact */
    public static final int ACTION_INSERT_OR_EDIT_CONTACT = 80;

    /** Show all phone numbers and pick them when clicking */
    public static final int ACTION_PICK_PHONE = 90;

    /** Show all postal addresses and pick them when clicking */
    public static final int ACTION_PICK_POSTAL = 100;

    /** Show all postal addresses and pick them when clicking */
    public static final int ACTION_PICK_EMAIL = 105;

    /** Show all contacts and create a shortcut for the picked contact */
    public static final int ACTION_CREATE_SHORTCUT_CONTACT = 110;

    /** Show all phone numbers and create a call shortcut for the picked number */
    public static final int ACTION_CREATE_SHORTCUT_CALL = 120;

    /** Show all phone numbers and create an SMS shortcut for the picked number */
    public static final int ACTION_CREATE_SHORTCUT_SMS = 130;

    /** Show all contacts and activate the specified one */
    public static final int ACTION_VIEW_CONTACT = 140;

    /** Show contacts recommended for joining with a specified target contact */
    public static final int ACTION_PICK_JOIN = 150;

    private boolean mValid = true;
    private int mActionCode = ACTION_DEFAULT;
    private CharSequence mTitle;
    private boolean mSearchMode;
    private String mQueryString;
    private boolean mIncludeProfile;
    private boolean mLegacyCompatibilityMode;
    private boolean mDirectorySearchEnabled = true;
    private Uri mContactUri;

    @Override
    public String toString() {
        return "{ContactsRequest:mValid=" + mValid
                + " mActionCode=" + mActionCode
                + " mTitle=" + mTitle
                + " mSearchMode=" + mSearchMode
                + " mQueryString=" + mQueryString
                + " mIncludeProfile=" + mIncludeProfile
                + " mLegacyCompatibilityMode=" + mLegacyCompatibilityMode
                + " mDirectorySearchEnabled=" + mDirectorySearchEnabled
                + " mContactUri=" + mContactUri
                + "}";
    }

    public boolean isValid() {
        return mValid;
    }

    public void setValid(boolean flag) {
        mValid = flag;
    }

    public void setActivityTitle(CharSequence title) {
        mTitle = title;
    }

    public CharSequence getActivityTitle() {
        return mTitle;
    }

    public int getActionCode() {
        return mActionCode;
    }

    public void setActionCode(int actionCode) {
        mActionCode = actionCode;
    }

    public boolean isSearchMode() {
        return mSearchMode;
    }

    public void setSearchMode(boolean flag) {
        mSearchMode = flag;
    }

    public String getQueryString() {
        return mQueryString;
    }

    public void setQueryString(String string) {
        mQueryString = string;
    }

    public boolean shouldIncludeProfile() {
        return mIncludeProfile;
    }

    public void setIncludeProfile(boolean includeProfile) {
        mIncludeProfile = includeProfile;
    }

    public boolean isLegacyCompatibilityMode() {
        return mLegacyCompatibilityMode;
    }

    public void setLegacyCompatibilityMode(boolean flag) {
        mLegacyCompatibilityMode = flag;
    }

    /**
     * Determines whether this search request should include directories or
     * is limited to local contacts only.
     */
    public boolean isDirectorySearchEnabled() {
        return mDirectorySearchEnabled;
    }

    public void setDirectorySearchEnabled(boolean flag) {
        mDirectorySearchEnabled = flag;
    }

    public Uri getContactUri() {
        return mContactUri;
    }

    public void setContactUri(Uri contactUri) {
        this.mContactUri = contactUri;
    }

    /**
    * SPRD:Bug 474752 Add features with multi-selection activity in Contacts.
    *
    * @{
    */
    public static final int ACTION_MULTI_PICK_START = 200;
    public static final int ACTION_MULTI_PICK_CONTACT = 201;
    public static final int ACTION_MULTI_PICK_PHONE = 202;
    public static final int ACTION_MULTI_PICK_EMAIL = 203;
    public static final int ACTION_MULTI_PICK_ALL_IN_ONE_DATA = 204;
    public static final int ACTION_MULTI_PICK_ALL_IN_ONE_DATA_NEWUI = 205;
    public static final int ACTION_MULTI_PICK_END = 210;

    private List<String> mCascadingData;

    public void setCascadingData(List<String> data) {
    mCascadingData=data;
    }

    public List<String> getCascadingData() {
    return mCascadingData;
    }
    /**
    * @}
    */
}
