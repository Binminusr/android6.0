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

package com.android.contacts.common.list;

import android.app.ActionBar;
import android.app.Activity;
import android.app.LoaderManager.LoaderCallbacks;
import android.content.AsyncTaskLoader;
import android.content.Context;
import android.content.Intent;
import android.content.Loader;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ListView;

import com.android.contacts.common.R;
import com.android.contacts.common.model.AccountTypeManager;
import com.android.contacts.common.model.account.AccountType;
import com.android.contacts.common.model.account.AccountWithDataSet;
import com.google.common.collect.Lists;

import java.util.ArrayList;
import java.util.List;

/**
 * SPRD:
 * @{
 */
import com.android.contacts.common.preference.ContactsPreferences;
import android.content.SharedPreferences.Editor;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.widget.CheckBox;
import android.widget.TextView;
import android.text.TextUtils.TruncateAt;
/**
 * @}
 */
/**
 * Shows a list of all available accounts, letting the user select under which account to view
 * contacts.
 */
public class AccountFilterActivity extends Activity implements AdapterView.OnItemClickListener {

    private static final String TAG = AccountFilterActivity.class.getSimpleName();

    private static final int SUBACTIVITY_CUSTOMIZE_FILTER = 0;

    public static final String KEY_EXTRA_CONTACT_LIST_FILTER = "contactListFilter";
    public static final String KEY_EXTRA_CURRENT_FILTER = "currentFilter";

    private static final int FILTER_LOADER_ID = 0;

    private ListView mListView;

    private ContactListFilter mCurrentFilter;
    /**
     * SPRD: Bug474736 Display only with phone number
     * @{
     */
    private static SharedPreferences mSharedPreferences;
    private CheckBox mOnlyPhones;
    private View mHeaderPhones;
    private Boolean isChecked;
    /**
     * @}
     */

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        setContentView(R.layout.contact_list_filter);

        mListView = (ListView) findViewById(android.R.id.list);
        mListView.setOnItemClickListener(this);
        /**
         * SPRD: Bug474736 Display only with phone number.
         * @{
         */
        mSharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        createWithPhonesOnlyView();
        /**
         * @}
         */
        ActionBar actionBar = getActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
        }

        mCurrentFilter = getIntent().getParcelableExtra(KEY_EXTRA_CURRENT_FILTER);

        getLoaderManager().initLoader(FILTER_LOADER_ID, null, new MyLoaderCallbacks());
    }

    /**
     * SPRD: Bug474736 Display only with phone number.
     * @{
     */
    private void createWithPhonesOnlyView() {
        // Add the "Only contacts with phones" header modifier.
        mHeaderPhones = findViewById(R.id.only_phone);
        mOnlyPhones = (CheckBox) mHeaderPhones.findViewById(R.id.checkbox);
        isChecked = mSharedPreferences.getBoolean(ContactsPreferences.PREF_DISPLAY_ONLY_PHONES,
                ContactsPreferences.PREF_DISPLAY_ONLY_PHONES_DEFAULT);
        mOnlyPhones.setChecked(isChecked);
        {
            final TextView title = (TextView) mHeaderPhones.findViewById(R.id.title);
            final TextView describe = (TextView) mHeaderPhones.findViewById(R.id.describe);
            title.setSingleLine(true);
            title.setEllipsize(TruncateAt.END);
            title.setText(R.string.list_contact_phone);
            describe.setText(R.string.list_filter_phones);
        }
        mHeaderPhones.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                mOnlyPhones.toggle();
                Editor editor = mSharedPreferences.edit();
                editor.putBoolean(ContactsPreferences.PREF_DISPLAY_ONLY_PHONES,
                        mOnlyPhones.isChecked());
                editor.apply();
                if (isChecked != mOnlyPhones.isChecked()) {
                    mCurrentFilter.onlyPhonesChanged = 1;
                }
            }
        });
    }
    /**
     * @}
     */

    private static class FilterLoader extends AsyncTaskLoader<List<ContactListFilter>> {
        private Context mContext;

        public FilterLoader(Context context) {
            super(context);
            mContext = context;
        }

        @Override
        public List<ContactListFilter> loadInBackground() {
            return loadAccountFilters(mContext);
        }

        @Override
        protected void onStartLoading() {
            forceLoad();
        }

        @Override
        protected void onStopLoading() {
            cancelLoad();
        }

        @Override
        protected void onReset() {
            onStopLoading();
        }
    }

    private static List<ContactListFilter> loadAccountFilters(Context context) {
        final ArrayList<ContactListFilter> result = Lists.newArrayList();
        final ArrayList<ContactListFilter> accountFilters = Lists.newArrayList();
        final AccountTypeManager accountTypes = AccountTypeManager.getInstance(context);
        List<AccountWithDataSet> accounts = accountTypes.getAccounts(false);
        for (AccountWithDataSet account : accounts) {
            AccountType accountType = accountTypes.getAccountType(account.type, account.dataSet);
            if (accountType.isExtension() && !account.hasData(context)) {
                // Hide extensions with no raw_contacts.
                continue;
            }
            Drawable icon = accountType != null ? accountType.getDisplayIcon(context) : null;
            accountFilters.add(ContactListFilter.createAccountFilter(
                    account.type, account.name, account.dataSet, icon));
        }

        // Always show "All", even when there's no accounts.  (We may have local contacts)
        result.add(ContactListFilter.createFilterWithType(
                ContactListFilter.FILTER_TYPE_ALL_ACCOUNTS));

        final int count = accountFilters.size();
        if (count >= 1) {
            // If we only have one account, don't show it as "account", instead show it as "all"
            if (count > 1) {
                result.addAll(accountFilters);
            }
            result.add(ContactListFilter.createFilterWithType(
                    ContactListFilter.FILTER_TYPE_CUSTOM));
        }
        return result;
    }

    private class MyLoaderCallbacks implements LoaderCallbacks<List<ContactListFilter>> {
        @Override
        public Loader<List<ContactListFilter>> onCreateLoader(int id, Bundle args) {
            return new FilterLoader(AccountFilterActivity.this);
        }

        @Override
        public void onLoadFinished(
                Loader<List<ContactListFilter>> loader, List<ContactListFilter> data) {
            if (data == null) { // Just in case...
                Log.e(TAG, "Failed to load filters");
                return;
            }
            mListView.setAdapter(
                    new FilterListAdapter(AccountFilterActivity.this, data, mCurrentFilter));
        }

        @Override
        public void onLoaderReset(Loader<List<ContactListFilter>> loader) {
        }
    }

    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        final ContactListFilter filter = (ContactListFilter) view.getTag();
        // SPRD:Bug 519402 CID:72202 Dereference before null check
        if (filter == null) {
            return;
        }
        // SPRD:Bug258382
        filter.onlyPhonesChanged = mCurrentFilter.onlyPhonesChanged;
        // SPRD:Bug258382
        if (filter.filterType == ContactListFilter.FILTER_TYPE_CUSTOM) {
            final Intent intent = new Intent(this,
                    CustomContactListFilterActivity.class);
            startActivityForResult(intent, SUBACTIVITY_CUSTOMIZE_FILTER);
        } else {
            final Intent intent = new Intent();
            intent.putExtra(KEY_EXTRA_CONTACT_LIST_FILTER, filter);
          /**
           * SPRD:Bug258382
           * @{
           */
            ContactListFilter.storeToPreferences(mSharedPreferences,filter);
          /**
           * @}
           */
            setResult(Activity.RESULT_OK, intent);
            finish();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (resultCode != Activity.RESULT_OK) {
            return;
        }

        switch (requestCode) {
            case SUBACTIVITY_CUSTOMIZE_FILTER: {
                final Intent intent = new Intent();
                ContactListFilter filter = ContactListFilter.createFilterWithType(
                        ContactListFilter.FILTER_TYPE_CUSTOM);
                intent.putExtra(KEY_EXTRA_CONTACT_LIST_FILTER, filter);
                setResult(Activity.RESULT_OK, intent);
                finish();
                break;
            }
        }
    }

    private static class FilterListAdapter extends BaseAdapter {
        private final List<ContactListFilter> mFilters;
        private final LayoutInflater mLayoutInflater;
        private final AccountTypeManager mAccountTypes;
        private final ContactListFilter mCurrentFilter;

        public FilterListAdapter(
                Context context, List<ContactListFilter> filters, ContactListFilter current) {
            mLayoutInflater = (LayoutInflater) context.getSystemService
                    (Context.LAYOUT_INFLATER_SERVICE);
            mFilters = filters;
            mCurrentFilter = current;
            mAccountTypes = AccountTypeManager.getInstance(context);
        }

        @Override
        public int getCount() {
            return mFilters.size();
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public ContactListFilter getItem(int position) {
            return mFilters.get(position);
        }

        public View getView(int position, View convertView, ViewGroup parent) {
            final ContactListFilterView view;
            if (convertView != null) {
                view = (ContactListFilterView) convertView;
            } else {
                view = (ContactListFilterView) mLayoutInflater.inflate(
                        R.layout.contact_list_filter_item, parent, false);
            }
            view.setSingleAccount(mFilters.size() == 1);
            final ContactListFilter filter = mFilters.get(position);
            view.setContactListFilter(filter);
            view.bindView(mAccountTypes);
            view.setTag(filter);
            /**
             * SPRD: add for Bug259575
             * orig
            view.setActivated(filter.equals(mCurrentFilter));
             * @{
             */
            view.setActivated(filter.toString().equals(mCurrentFilter.toString()));
            /**
             * @}
             */
            return view;
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home:
                // We have two logical "up" Activities: People and Phone.
                // Instead of having one static "up" direction, behave like back as an
                // exceptional case.
                onBackPressed();
                return true;
            default:
                break;
        }
        return super.onOptionsItemSelected(item);
    }
    /**
    * SPRD: Bug474736 Display only with phone number.
    * @{
    */
    @Override
    public void onBackPressed() {
        if (isChecked != mSharedPreferences.getBoolean(
                ContactsPreferences.PREF_DISPLAY_ONLY_PHONES,
                ContactsPreferences.PREF_DISPLAY_ONLY_PHONES_DEFAULT)) {
            //SPRD:Bug258382
            mCurrentFilter.onlyPhonesChanged = 1;
            //SPRD:Bug258382
            final Intent intent = new Intent();
            intent.putExtra(KEY_EXTRA_CONTACT_LIST_FILTER, mCurrentFilter);
            setResult(Activity.RESULT_OK, intent);
            finish();
        } else {
            super.onBackPressed();
        }
        return;
    }
    /**
    * @}
    */
}
