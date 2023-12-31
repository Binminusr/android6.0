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

package com.android.contacts.editor;

import android.content.Intent;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnShowListener;
import android.os.Bundle;
import android.os.Handler;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.util.Log;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemSelectedListener;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import com.android.contacts.R;
import com.android.contacts.common.model.RawContactDelta;
import com.android.contacts.common.ContactsUtils;
import com.android.contacts.common.model.ValuesDelta;
import com.android.contacts.common.model.RawContactModifier;
import com.android.contacts.common.model.account.AccountType.EditType;
import com.android.contacts.common.model.dataitem.DataKind;
import com.android.contacts.util.DialogManager;
import com.android.contacts.util.DialogManager.DialogShowingView;

import java.util.ArrayList;
import java.util.List;

import android.widget.AdapterView.OnItemClickListener;
import com.sprd.contacts.plugin.EFDisplayUtils;

/**
 * Base class for editors that handles labels and values. Uses
 * {@link ValuesDelta} to read any existing {@link RawContact} values, and to
 * correctly write any changes values.
 */
public abstract class LabeledEditorView extends LinearLayout implements Editor, DialogShowingView {
    protected static final String DIALOG_ID_KEY = "dialog_id";
    private static final int DIALOG_ID_CUSTOM = 1;

    private static final int INPUT_TYPE_CUSTOM = EditorInfo.TYPE_CLASS_TEXT
            | EditorInfo.TYPE_TEXT_FLAG_CAP_WORDS;

    private Spinner mLabel;
    private EditTypeAdapter mEditTypeAdapter;
    private View mDeleteContainer;
    private ImageView mDelete;

    private DataKind mKind;
    private ValuesDelta mEntry;
    private RawContactDelta mState;
    private boolean mReadOnly;
    private boolean mWasEmpty = true;
    private boolean mIsDeletable = true;
    private boolean mIsAttachedToWindow;
    private boolean mHideTypeInitially;
    private boolean mHasTypes;

    private EditType mType;

    private ViewIdGenerator mViewIdGenerator;
    private DialogManager mDialogManager = null;
    private EditorListener mListener;
    protected int mMinLineItemHeight;

    /**
     * A marker in the spinner adapter of the currently selected custom type.
     */
    public static final EditType CUSTOM_SELECTION = new EditType(0, 0);

    private OnItemSelectedListener mSpinnerListener = new OnItemSelectedListener() {

        @Override
        public void onItemSelected(
                AdapterView<?> parent, View view, int position, long id) {
            onTypeSelectionChange(position);
        }

        @Override
        public void onNothingSelected(AdapterView<?> parent) {
        }
    };

    public LabeledEditorView(Context context) {
        super(context);
        init(context);
    }

    public LabeledEditorView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public LabeledEditorView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init(context);
    }

    /* SPRD: 443449 orange_ef anr/aas/sne @{ */
    private OnItemClickListener mSpinnerClickListener = new OnItemClickListener() {
        @Override
        public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
            onTypeSelectionChange(position);
        }
    };

    public Long getRawContactId() {
        return mState == null ? null : mState.getRawContactId();
    }

    private void init(Context context) {
        mMinLineItemHeight = context.getResources().getDimensionPixelSize(
                R.dimen.editor_min_line_item_height);
    }

    /** {@inheritDoc} */
    @Override
    protected void onFinishInflate() {
        mLabel = (Spinner) findViewById(R.id.spinner);
        // Turn off the Spinner's own state management. We do this ourselves on rotation
        mLabel.setId(View.NO_ID);
        mLabel.setOnItemSelectedListener(mSpinnerListener);
        /* SPRD: 443449 orange_ef anr/aas/sne @{ */
        if (EFDisplayUtils.getInstance().isOrangeSupport() && isUsimAccountType(mState)) {
            mLabel.setOnItemClickListenerInt(mSpinnerClickListener);
        }
        mDelete = (ImageView) findViewById(R.id.delete_button);
        mDeleteContainer = findViewById(R.id.delete_button_container);
        mDeleteContainer.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                // defer removal of this button so that the pressed state is visible shortly
                new Handler().post(new Runnable() {
                    @Override
                    public void run() {
                        // Don't do anything if the view is no longer attached to the window
                        // (This check is needed because when this {@link Runnable} is executed,
                        // we can't guarantee the view is still valid.
                        if (!mIsAttachedToWindow) {
                            return;
                        }
                        // Send the delete request to the listener (which will in turn call
                        // deleteEditor() on this view if the deletion is valid - i.e. this is not
                        // the last {@link Editor} in the section).
                        if (mListener != null) {
                            mListener.onDeleteRequested(LabeledEditorView.this);
                        }
                    }
                });
            }
        });

        setPadding(getPaddingLeft(), getPaddingTop(), getPaddingRight(),
                (int) getResources().getDimension(R.dimen.editor_padding_between_editor_views));
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        // Keep track of when the view is attached or detached from the window, so we know it's
        // safe to remove views (in case the user requests to delete this editor).
        mIsAttachedToWindow = true;
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mIsAttachedToWindow = false;
    }

    @Override
    public void markDeleted() {
        // Keep around in model, but mark as deleted
        mEntry.markDeleted();
    }

    @Override
    public void deleteEditor() {
        markDeleted();

        // Remove the view
        EditorAnimator.getInstance().removeEditorView(this);
    }

    public boolean isReadOnly() {
        return mReadOnly;
    }

    public int getBaseline(int row) {
        if (row == 0 && mLabel != null) {
            return mLabel.getBaseline();
        }
        return -1;
    }

    /**
     * Configures the visibility of the type label button and enables or disables it properly.
     */
    private void setupLabelButton(boolean shouldExist) {
        if (shouldExist) {
            mLabel.setEnabled(!mReadOnly && isEnabled());
            mLabel.setVisibility(View.VISIBLE);
        } else {
            mLabel.setVisibility(View.GONE);
        }
    }

    /**
     * Configures the visibility of the "delete" button and enables or disables it properly.
     */
    private void setupDeleteButton() {
        if (mIsDeletable) {
            mDeleteContainer.setVisibility(View.VISIBLE);
            mDelete.setEnabled(!mReadOnly && isEnabled());
        } else {
            mDeleteContainer.setVisibility(View.GONE);
        }
    }

    public void setDeleteButtonVisible(boolean visible) {
        if (mIsDeletable) {
            mDeleteContainer.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
        }
    }

    /**
     * Whether to hide the type dropdown after values have been set.
     * By default the drop down is always displayed if there are types to display.
     */
    public void setHideTypeInitially(boolean hideTypeInitially) {
        mHideTypeInitially = hideTypeInitially;
    }

    /**
     * Whether the type drop down is visible.
     */
    public boolean isTypeVisible() {
        return mLabel == null ? false : mLabel.getVisibility() == View.VISIBLE;
    }

    /**
     * Makes the type drop down visible if it is not already so, and there are types to display.
     */
    public void showType() {
        if (mHasTypes && mLabel != null && mLabel.getVisibility() != View.VISIBLE) {
            EditorAnimator.getInstance().slideAndFadeIn(mLabel, mLabel.getHeight());
        }
    }

    /**
     * Hides the type drop down if there are types to display and it is not already hidden.
     */
    public void hideType() {
        if (mHasTypes && mLabel != null && mLabel.getVisibility() != View.GONE) {
            EditorAnimator.getInstance().hideEditorView(mLabel);
        }
    }

    protected void onOptionalFieldVisibilityChange() {
        if (mListener != null) {
            mListener.onRequest(EditorListener.EDITOR_FORM_CHANGED);
        }
    }

    @Override
    public void setEditorListener(EditorListener listener) {
        mListener = listener;
    }

    protected EditorListener getEditorListener(){
        return mListener;
    }

    @Override
    public void setDeletable(boolean deletable) {
        mIsDeletable = deletable;
        setupDeleteButton();
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        mLabel.setEnabled(!mReadOnly && enabled);
        mDelete.setEnabled(!mReadOnly && enabled);
    }

    public Spinner getLabel() {
        return mLabel;
    }

    public ImageView getDelete() {
        return mDelete;
    }

    protected DataKind getKind() {
        return mKind;
    }

    protected ValuesDelta getEntry() {
        return mEntry;
    }

    protected EditType getType() {
        return mType;
    }

    /**
     * Build the current label state based on selected {@link EditType} and
     * possible custom label string.
     */
    public void rebuildLabel() {
        mEditTypeAdapter = new EditTypeAdapter(getContext());
        mLabel.setAdapter(mEditTypeAdapter);
        if (mEditTypeAdapter.hasCustomSelection()) {
            mLabel.setSelection(mEditTypeAdapter.getPosition(CUSTOM_SELECTION));
        } else {
            mLabel.setSelection(mEditTypeAdapter.getPosition(mType));
        }
    }

    @Override
    public void onFieldChanged(String column, String value) {
        if (!isFieldChanged(column, value)) {
            return;
        }
        // Field changes are saved directly
        saveValue(column, value);

        // Notify listener if applicable
        notifyEditorListener();
    }

    protected void saveValue(String column, String value) {
        mEntry.put(column, value);
        /* SPRD: 443449 orange_ef anr/aas/sne @{ */
        EFDisplayUtils.getInstance().setAasValue(getContext(), mEntry, mLabel);
    }

    /**
     * Sub classes should call this at the end of {@link #setValues} once they finish changing
     * isEmpty(). This is needed to fix b/18194655.
     */
    protected final void updateEmptiness() {
        mWasEmpty = isEmpty();
    }

    protected void notifyEditorListener() {
        if (mListener != null) {
            mListener.onRequest(EditorListener.FIELD_CHANGED);
        }

        boolean isEmpty = isEmpty();
        if (mWasEmpty != isEmpty) {
            if (isEmpty) {
                if (mListener != null) {
                    mListener.onRequest(EditorListener.FIELD_TURNED_EMPTY);
                }
                if (mIsDeletable) mDeleteContainer.setVisibility(View.INVISIBLE);
            } else {
                if (mListener != null) {
                    mListener.onRequest(EditorListener.FIELD_TURNED_NON_EMPTY);
                }
                if (mIsDeletable) mDeleteContainer.setVisibility(View.VISIBLE);
            }
            mWasEmpty = isEmpty;
        }
    }

    protected boolean isFieldChanged(String column, String value) {
        final String dbValue = mEntry.getAsString(column);
        // nullable fields (e.g. Middle Name) are usually represented as empty columns,
        // so lets treat null and empty space equivalently here
        final String dbValueNoNull = dbValue == null ? "" : dbValue;
        final String valueNoNull = value == null ? "" : value;
        return !TextUtils.equals(dbValueNoNull, valueNoNull);
    }

    protected void rebuildValues() {
        setValues(mKind, mEntry, mState, mReadOnly, mViewIdGenerator);
    }

    /**
     * Prepare this editor using the given {@link DataKind} for defining structure and
     * {@link ValuesDelta} describing the content to edit. When overriding this, be careful
     * to call {@link #updateEmptiness} at the end.
     */
    @Override
    public void setValues(DataKind kind, ValuesDelta entry, RawContactDelta state, boolean readOnly,
            ViewIdGenerator vig) {
        mKind = kind;
        mEntry = entry;
        mState = state;
        mReadOnly = readOnly;
        mViewIdGenerator = vig;
        setId(vig.getId(state, kind, entry, ViewIdGenerator.NO_VIEW_INDEX));

        if (!entry.isVisible()) {
            // Hide ourselves entirely if deleted
            setVisibility(View.GONE);
            return;
        }
        setVisibility(View.VISIBLE);

        // Display label selector if multiple types available
        mHasTypes = RawContactModifier.hasEditTypes(kind);
        setupLabelButton(mHasTypes);
        mLabel.setEnabled(!readOnly && isEnabled());
        if (mHasTypes) {
            mType = RawContactModifier.getCurrentType(entry, kind);
            rebuildLabel();
            if (mHideTypeInitially) {
                mLabel.setVisibility(View.GONE);
            }
        }
    }

    public ValuesDelta getValues() {
        return mEntry;
    }

    /**
     * SPRD:Bug522594 The edit page can't be transferred when using Exchange account.
     * @{
     */
    public RawContactDelta getRawContactDelta() {
        return mState;
    }
    /**
     * @}
     */

    /**
     * Prepare dialog for entering a custom label. The input value is trimmed: white spaces before
     * and after the input text is removed.
     * <p>
     * If the final value is empty, this change request is ignored;
     * no empty text is allowed in any custom label.
     */
    private Dialog createCustomDialog() {
        final AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        final LayoutInflater layoutInflater = LayoutInflater.from(builder.getContext());
        builder.setTitle(R.string.customLabelPickerTitle);

        final View view = layoutInflater.inflate(R.layout.contact_editor_label_name_dialog, null);
        final EditText editText = (EditText) view.findViewById(R.id.custom_dialog_content);
        editText.setInputType(INPUT_TYPE_CUSTOM);
        editText.setSaveEnabled(true);

        builder.setView(view);
        editText.requestFocus();

        builder.setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                final String customText = editText.getText().toString().trim();
                if (ContactsUtils.isGraphic(customText)) {
                    final List<EditType> allTypes =
                            RawContactModifier.getValidTypes(mState, mKind, null);
                    mType = null;
                    for (EditType editType : allTypes) {
                        if (editType.customColumn != null) {
                            mType = editType;
                            break;
                        }
                    }
                    if (mType == null) return;

                    mEntry.put(mKind.typeColumn, mType.rawValue);
                    mEntry.put(mType.customColumn, customText);
                    rebuildLabel();
                    requestFocusForFirstEditField();
                    onLabelRebuilt();
                }
            }
        });
        /**
         * SPRD:Bug 532335Display type and save type isn't the same while cancel custom dialog
         * original:builder.setNegativeButton(android.R.string.cancel, null);
         *
         * @{
         */
        builder.setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                rebuildLabel();
            }
        });
        /**
         * @}
         */
        final AlertDialog dialog = builder.create();
        dialog.setOnShowListener(new OnShowListener() {
            @Override
            public void onShow(DialogInterface dialogInterface) {
                updateCustomDialogOkButtonState(dialog, editText);
            }
        });
        editText.addTextChangedListener(new TextWatcher() {
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                updateCustomDialogOkButtonState(dialog, editText);
            }
        });
        dialog.getWindow().setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);

        return dialog;
    }

    /* package */ void updateCustomDialogOkButtonState(AlertDialog dialog, EditText editText) {
        final Button okButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        okButton.setEnabled(!TextUtils.isEmpty(editText.getText().toString().trim()));
    }

    /**
     * Called after the label has changed (either chosen from the list or entered in the Dialog)
     */
    protected void onLabelRebuilt() {
    }

    protected void onTypeSelectionChange(int position) {
        EditType selected = mEditTypeAdapter.getItem(position);
        // See if the selection has in fact changed
        if (mEditTypeAdapter.hasCustomSelection() && selected == CUSTOM_SELECTION) {
            return;
        }

        if (mType == selected && mType.customColumn == null) {
            return;
        }
        if (selected.customColumn != null) {
            /* SPRD: 443449 orange_ef anr/aas/sne @{ */
            if (isUsimAccountType(mState) && EFDisplayUtils.getInstance().isOrangeSupport()) {
                EFDisplayUtils.getInstance().startAasEditActivity(getContext());
            } else {
                showDialog(DIALOG_ID_CUSTOM);
            }
        } else {
            // User picked type, and we're sure it's ok to actually write the entry.
            mType = selected;
            mEntry.put(mKind.typeColumn, mType.rawValue);
            rebuildLabel();
            requestFocusForFirstEditField();
            onLabelRebuilt();
            /* SPRD: 443449 orange_ef anr/aas/sne @{ */
            if (EFDisplayUtils.getInstance().isOrangeSupport()) {
                EFDisplayUtils.getInstance().setAasValue(getContext(), mEntry, mLabel);
            }
        }
    }

    /* package */
    void showDialog(int bundleDialogId) {
        Bundle bundle = new Bundle();
        bundle.putInt(DIALOG_ID_KEY, bundleDialogId);
        getDialogManager().showDialogInView(this, bundle);
    }

    private DialogManager getDialogManager() {
        if (mDialogManager == null) {
            Context context = getContext();
            if (!(context instanceof DialogManager.DialogShowingViewActivity)) {
                throw new IllegalStateException(
                        "View must be hosted in an Activity that implements " +
                        "DialogManager.DialogShowingViewActivity");
            }
            mDialogManager = ((DialogManager.DialogShowingViewActivity)context).getDialogManager();
        }
        return mDialogManager;
    }

    @Override
    public Dialog createDialog(Bundle bundle) {
        if (bundle == null) throw new IllegalArgumentException("bundle must not be null");
        int dialogId = bundle.getInt(DIALOG_ID_KEY);
        switch (dialogId) {
            case DIALOG_ID_CUSTOM:
                return createCustomDialog();
            default:
                throw new IllegalArgumentException("Invalid dialogId: " + dialogId);
        }
    }

    /* SPRD: 443449 orange_ef anr/aas/sne @{ */
    private boolean isUsimAccountType(RawContactDelta state) {
        if (state == null) {
            return false;
        }
        /**
         * SPRD bug539021 After monkey test, bring Contacts crash
         * @}
         */
        if (state.getValues().getAsString("account_type") == null) {
            return false;
        }
        /**
         * @}
         */
        boolean isUsimAccountType = state.getValues().getAsString("account_type").equals("sprd.com.android.account.usim");
        return isUsimAccountType;
    }

    /**
     * SPRD:Bug498039 Dismiss the custom label dialog, when lock the screen.
     * @{
     */
    public void dissmissDialog() {
        // add for Bug 532335Display type and save type isn't the same while cancel custom dialog
        requestFocusForFirstEditField();
        getDialogManager().dismissDialog();
    }
    /**
     * @}
     */

    protected abstract void requestFocusForFirstEditField();

    private class EditTypeAdapter extends ArrayAdapter<EditType> {
        private final LayoutInflater mInflater;
        private boolean mHasCustomSelection;
        private int mTextColorHintUnfocused;
        private int mTextColorDark;

        public EditTypeAdapter(Context context) {
            super(context, 0);
            mInflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
            mTextColorHintUnfocused = context.getResources().getColor(
                    R.color.editor_disabled_text_color);
            mTextColorDark = context.getResources().getColor(R.color.primary_text_color);

            /* SPRD:443449  orange_ef anr/aas/sne @{ */
            EFDisplayUtils.getInstance().updateValidType(mState, mKind, mType);
            /* @} */

            if (mType != null && mType.customColumn != null) {

                // Use custom label string when present
                final String customText = mEntry.getAsString(mType.customColumn);

                if (customText != null) {
                    add(CUSTOM_SELECTION);
                    mHasCustomSelection = true;
                    /* SPRD: 443449 orange_ef anr/aas/sne @{ */
                    EFDisplayUtils.getInstance().removeTypeFromValid(customText, mEntry);
                    /* @} */
                }
            }
            /**
             * SPRD: 443449 orange_ef anr/aas/sne
             * @orig:
             * addAll(RawContactModifier.getValidTypes(mState, mKind, mType));
             * @{
             */
            ArrayList<EditType> validTypes = EFDisplayUtils.getInstance().getValidTypes(mState, mKind, mType);
            addAll(validTypes);
            EFDisplayUtils.getInstance().clearValidTypes();
            /* @} */
        }

        public boolean hasCustomSelection() {
            return mHasCustomSelection;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            final TextView view = createViewFromResource(
                    position, convertView, parent, R.layout.edit_simple_spinner_item);
            // We don't want any background on this view. The background would obscure
            // the spinner's background.
            view.setBackground(null);
            // The text color should be a very light hint color when unfocused and empty. When
            // focused and empty, use a less light hint color. When non-empty, use a dark non-hint
            // color.
            if (!LabeledEditorView.this.isEmpty()) {
                view.setTextColor(mTextColorDark);
            } else {
                view.setTextColor(mTextColorHintUnfocused);
            }
            return view;
        }

        @Override
        public View getDropDownView(int position, View convertView, ViewGroup parent) {
            return createViewFromResource(
                    position, convertView, parent, android.R.layout.simple_spinner_dropdown_item);
        }

        private TextView createViewFromResource(int position, View convertView, ViewGroup parent,
                int resource) {
            TextView textView;

            if (convertView == null) {
                textView = (TextView) mInflater.inflate(resource, parent, false);
                textView.setTextSize(TypedValue.COMPLEX_UNIT_PX, getResources().getDimension(
                        R.dimen.editor_form_text_size));
                textView.setTextColor(mTextColorDark);
            } else {
                textView = (TextView) convertView;
            }

            EditType type = getItem(position);
            /* SPRD: 443449 orange_ef anr/aas/sne @{ */
            String text = EFDisplayUtils.getInstance().getText(getContext(), type, mType, mEntry);
            textView.setText(text);
            return textView;
        }
    }
}
