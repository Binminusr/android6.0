/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License
 */
package com.android.providers.contacts;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.provider.ContactsContract.CommonDataKinds.Email;
import com.android.providers.contacts.SearchIndexManager.IndexBuilder;
import com.android.providers.contacts.aggregation.AbstractContactAggregator;
/**
 * SPRD:
 *
 * @{
 */
import com.sprd.providers.contacts.ContactProxyManager;
import com.sprd.providers.contacts.SimContactProxyUtil;
/**
 * @}
 */

/**
 * Handler for email address data rows.
 */
public class DataRowHandlerForEmail extends DataRowHandlerForCommonDataKind {

    public DataRowHandlerForEmail(
            Context context, ContactsDatabaseHelper dbHelper, AbstractContactAggregator aggregator) {
        super(context, dbHelper, aggregator, Email.CONTENT_ITEM_TYPE, Email.TYPE, Email.LABEL);
    }

    @Override
    public long insert(SQLiteDatabase db, TransactionContext txContext, long rawContactId,
            ContentValues values) {
        /**
         * SPRD: create/update sim-card contact, bug421665
         *
         * @{
         */
        ContactProxyManager contactProxyManager = ContactProxyManager.getInstance(mContext);
        SimContactProxyUtil simProxyUtil = SimContactProxyUtil.getInstance(contactProxyManager, mContext);
        if (simProxyUtil.isMyContact(rawContactId)) {
            contactProxyManager.onDataUpdate(rawContactId, values, Email.CONTENT_ITEM_TYPE);
        }
        /**
         * @}
         */

        String email = values.getAsString(Email.DATA);

        long dataId = super.insert(db, txContext, rawContactId, values);

        fixRawContactDisplayName(db, txContext, rawContactId);
        String address = mDbHelper.insertNameLookupForEmail(rawContactId, dataId, email);
        if (address != null) {
            triggerAggregation(txContext, rawContactId);
        }
        return dataId;
    }

    @Override
    public boolean update(SQLiteDatabase db, TransactionContext txContext, ContentValues values,
            Cursor c, boolean callerIsSyncAdapter) {
        if (!super.update(db, txContext, values, c, callerIsSyncAdapter)) {
            return false;
        }

        if (values.containsKey(Email.DATA)) {
            long dataId = c.getLong(DataUpdateQuery._ID);
            long rawContactId = c.getLong(DataUpdateQuery.RAW_CONTACT_ID);

            /**
             * SPRD: create/update sim-card contact, bug421665
             *
             * @{
             */
            ContactProxyManager contactProxyManager = ContactProxyManager.getInstance(mContext);
            SimContactProxyUtil simProxyUtil = SimContactProxyUtil.getInstance(contactProxyManager, mContext);
            if (simProxyUtil.isMyContact(rawContactId)) {
                contactProxyManager.onDataUpdate(rawContactId, values, Email.CONTENT_ITEM_TYPE);
            }
            /**
             * @}
             */
            String address = values.getAsString(Email.DATA);
            mDbHelper.deleteNameLookup(dataId);
            mDbHelper.insertNameLookupForEmail(rawContactId, dataId, address);
            fixRawContactDisplayName(db, txContext, rawContactId);
            triggerAggregation(txContext, rawContactId);
        }

        return true;
    }

    @Override
    public int delete(SQLiteDatabase db, TransactionContext txContext, Cursor c) {
        long dataId = c.getLong(DataDeleteQuery._ID);
        long rawContactId = c.getLong(DataDeleteQuery.RAW_CONTACT_ID);

        int count = super.delete(db, txContext, c);

        /**
         * SPRD: create/update sim-card contact, bug421665
         *
         * @{
         */
        ContentValues tmp = new ContentValues();
        tmp.put(Email.DATA, (String) null);
        ContactProxyManager contactProxyManager = ContactProxyManager.getInstance(mContext);
        SimContactProxyUtil simProxyUtil = SimContactProxyUtil.getInstance(contactProxyManager, mContext);
        if (simProxyUtil.isMyContact(rawContactId)) {
            contactProxyManager.onDataUpdate(rawContactId, tmp, Email.CONTENT_ITEM_TYPE);
        }
        /**
         * @}
         */
        mDbHelper.deleteNameLookup(dataId);
        fixRawContactDisplayName(db, txContext, rawContactId);
        triggerAggregation(txContext, rawContactId);
        return count;
    }

    @Override
    protected int getTypeRank(int type) {
        switch (type) {
            case Email.TYPE_HOME: return 0;
            case Email.TYPE_WORK: return 1;
            case Email.TYPE_CUSTOM: return 2;
            case Email.TYPE_OTHER: return 3;
            default: return 1000;
        }
    }

    @Override
    public boolean containsSearchableColumns(ContentValues values) {
        return values.containsKey(Email.ADDRESS);
    }

    @Override
    public void appendSearchableData(IndexBuilder builder) {
        /**
         * SPRD:
         *
         * @{
         */
        super.appendSearchableData(builder);
        /**
         * @}
         */
        builder.appendContentFromColumn(Email.ADDRESS);
    }
}
