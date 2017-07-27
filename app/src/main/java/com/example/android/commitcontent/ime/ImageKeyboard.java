/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.example.android.commitcontent.ime;

import android.app.AppOpsManager;
import android.content.ClipDescription;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.inputmethodservice.InputMethodService;
import android.net.Uri;
import android.os.Build;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.RawRes;
import android.support.v13.view.inputmethod.EditorInfoCompat;
import android.support.v13.view.inputmethod.InputConnectionCompat;
import android.support.v13.view.inputmethod.InputContentInfoCompat;
import android.support.v4.content.FileProvider;
import android.support.v4.view.ViewPager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputBinding;
import android.view.inputmethod.InputConnection;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TableLayout;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;

import me.relex.circleindicator.CircleIndicator;


public class ImageKeyboard extends InputMethodService implements AdapterPager.MyClickListener {

    private static final String TAG = "ImageKeyboard";
    private static final String AUTHORITY = "com.example.android.commitcontent.ime.inputcontent";
    private static final String MIME_TYPE_GIF = "image/gif";
    private static final String MIME_TYPE_PNG = "image/png";
    private static final String MIME_TYPE_WEBP = "image/webp";

    private File mPngFile;
    private File mGifFile;
    private File mWebpFile;
    private Button mGifButton;
    private Button mPngButton;
    private Button mWebpButton;
    private TableLayout mTableLayout = null;
    private ViewPager mViewPager = null;

    private ArrayList<File> files = new ArrayList<>();

    private boolean isCommitContentSupported(
            @Nullable EditorInfo editorInfo, @NonNull String mimeType) {
        if (editorInfo == null) {
            return false;
        }

        final InputConnection ic = getCurrentInputConnection();
        if (ic == null) {
            return false;
        }

        if (!validatePackageName(editorInfo)) {
            return false;
        }

        final String[] supportedMimeTypes = EditorInfoCompat.getContentMimeTypes(editorInfo);
        for (String supportedMimeType : supportedMimeTypes) {
            if (ClipDescription.compareMimeTypes(mimeType, supportedMimeType)) {
                return true;
            }
        }
        return false;
    }

    private void doCommitContent(@NonNull String description, @NonNull String mimeType,
                                 @NonNull File file) {
        final EditorInfo editorInfo = getCurrentInputEditorInfo();

        // Validate packageName again just in case.
        if (!validatePackageName(editorInfo)) {
            return;
        }

        final Uri contentUri = FileProvider.getUriForFile(this, AUTHORITY, file);

        // As you as an IME author are most likely to have to implement your own content provider
        // to support CommitContent API, it is important to have a clear spec about what
        // applications are going to be allowed to access the content that your are going to share.
        final int flag;
        if (Build.VERSION.SDK_INT >= 25) {
            // On API 25 and later devices, as an analogy of Intent.FLAG_GRANT_READ_URI_PERMISSION,
            // you can specify InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION to give
            // a temporary read access to the recipient application without exporting your content
            // provider.
            flag = InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION;
        } else {
            // On API 24 and prior devices, we cannot rely on
            // InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION. You as an IME author
            // need to decide what access control is needed (or not needed) for content URIs that
            // you are going to expose. This sample uses Context.grantUriPermission(), but you can
            // implement your own mechanism that satisfies your own requirements.
            flag = 0;
            try {
                // TODO: Use revokeUriPermission to revoke as needed.
                grantUriPermission(
                        editorInfo.packageName, contentUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception e) {
                Log.e(TAG, "grantUriPermission failed packageName=" + editorInfo.packageName
                        + " contentUri=" + contentUri, e);
            }
        }

        final InputContentInfoCompat inputContentInfoCompat = new InputContentInfoCompat(
                contentUri,
                new ClipDescription(description, new String[]{mimeType}),
                null /* linkUrl */);
        boolean isAccepted = InputConnectionCompat.commitContent(
                getCurrentInputConnection(), getCurrentInputEditorInfo(), inputContentInfoCompat,
                flag, null);

        Log.d("KeyBoard", isAccepted + "");

        if (!isAccepted){
            Toast.makeText(this, "Cannot load attachment!!!", Toast.LENGTH_SHORT).show();
        }


    }

    private boolean validatePackageName(@Nullable EditorInfo editorInfo) {
        if (editorInfo == null) {
            return false;
        }
        final String packageName = editorInfo.packageName;
        if (packageName == null) {
            return false;
        }

        // In Android L MR-1 and prior devices, EditorInfo.packageName is not a reliable identifier
        // of the target application because:
        //   1. the system does not verify it [1]
        //   2. InputMethodManager.startInputInner() had filled EditorInfo.packageName with
        //      view.getContext().getPackageName() [2]
        // [1]: https://android.googlesource.com/platform/frameworks/base/+/a0f3ad1b5aabe04d9eb1df8bad34124b826ab641
        // [2]: https://android.googlesource.com/platform/frameworks/base/+/02df328f0cd12f2af87ca96ecf5819c8a3470dc8
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return true;
        }

        final InputBinding inputBinding = getCurrentInputBinding();
        if (inputBinding == null) {
            // Due to b.android.com/225029, it is possible that getCurrentInputBinding() returns
            // null even after onStartInputView() is called.
            // TODO: Come up with a way to work around this bug....
            Log.e(TAG, "inputBinding should not be null here. "
                    + "You are likely to be hitting b.android.com/225029");
            return false;
        }
        final int packageUid = inputBinding.getUid();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            final AppOpsManager appOpsManager =
                    (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
            try {
                appOpsManager.checkPackage(packageUid, packageName);
            } catch (Exception e) {
                return false;
            }
            return true;
        }

        final PackageManager packageManager = getPackageManager();
        final String possiblePackageNames[] = packageManager.getPackagesForUid(packageUid);
        for (final String possiblePackageName : possiblePackageNames) {
            if (packageName.equals(possiblePackageName)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        files.clear();
        // TODO: Avoid file I/O in the main thread.
        final File imagesDir = new File(getFilesDir(), "images");
        imagesDir.mkdirs();
        mGifFile = getFileForResource(this, R.raw.alhum, imagesDir, "alhum.gif");
        files.add(mGifFile);
        mGifFile = getFileForResource(this, R.raw.ay_dios, imagesDir, "ay_dios.gif");
        files.add(mGifFile);
        mGifFile = getFileForResource(this, R.raw.bad_bitch, imagesDir, "bad_bitch.gif");
        files.add(mGifFile);
        mGifFile = getFileForResource(this, R.raw.bitch_please, imagesDir, "bitch_please.gif");
        files.add(mGifFile);
        mGifFile = getFileForResource(this, R.raw.black_girl_magic, imagesDir, "black_girl_magic.gif");
        files.add(mGifFile);
        mGifFile = getFileForResource(this, R.raw.bumbo, imagesDir, "bumbo.gif");
        files.add(mGifFile);
        mGifFile = getFileForResource(this, R.raw.callate, imagesDir, "callate.gif");
        files.add(mGifFile);
        mGifFile = getFileForResource(this, R.raw.cono, imagesDir, "cono.gif");
        files.add(mGifFile);
        mGifFile = getFileForResource(this, R.raw.dope, imagesDir, "dope.gif");
        files.add(mGifFile);
        mGifFile = getFileForResource(this, R.raw.dueces, imagesDir, "dueces.gif");
        files.add(mGifFile);
        mGifFile = getFileForResource(this, R.raw.eeeh, imagesDir, "eeeh.gif");
        files.add(mGifFile);
        mGifFile = getFileForResource(this, R.raw.eyebrows_on_fleek, imagesDir, "eyebrows_on_fleek.gif");
        files.add(mGifFile);
        mGifFile = getFileForResource(this, R.raw.f_da_haters, imagesDir, "f_da_haters.gif");
        files.add(mGifFile);
        mGifFile = getFileForResource(this, R.raw.fight_the_power, imagesDir, "fight_the_power.gif");
        files.add(mGifFile);
        mGifFile = getFileForResource(this, R.raw.first_of_all, imagesDir, "first_of_all.gif");
        files.add(mGifFile);
        mGifFile = getFileForResource(this, R.raw.foh, imagesDir, "foh.gif");
        files.add(mGifFile);
        mGifFile = getFileForResource(this, R.raw.girl_bye, imagesDir, "girl_bye.gif");
        files.add(mGifFile);
        mGifFile = getFileForResource(this, R.raw.im_tight, imagesDir, "im_tight.gif");
        files.add(mGifFile);
        mGifFile = getFileForResource(this, R.raw.inshall, imagesDir, "inshall.gif");
        files.add(mGifFile);
        mGifFile = getFileForResource(this, R.raw.lol, imagesDir, "lol.gif");
        files.add(mGifFile);
        mGifFile = getFileForResource(this, R.raw.no_boo_boo, imagesDir, "no_boo_boo.gif");
        files.add(mGifFile);
        mGifFile = getFileForResource(this, R.raw.okaay, imagesDir, "okaay.gif");
        files.add(mGifFile);
        mGifFile = getFileForResource(this, R.raw.omg, imagesDir, "omg.gif");
        files.add(mGifFile);
        mGifFile = getFileForResource(this, R.raw.pendejo, imagesDir, "pendejo.gif");
        files.add(mGifFile);
        mGifFile = getFileForResource(this, R.raw.pretty_please, imagesDir, "pretty_please.gif");
        files.add(mGifFile);
        mGifFile = getFileForResource(this, R.raw.que_pasa, imagesDir, "que_pasa.gif");
        files.add(mGifFile);
        mGifFile = getFileForResource(this, R.raw.respect, imagesDir, "respect.gif");
        files.add(mGifFile);
        mGifFile = getFileForResource(this, R.raw.sip_tea, imagesDir, "sip_tea.gif");
        files.add(mGifFile);
        mGifFile = getFileForResource(this, R.raw.slay, imagesDir, "slay.gif");
        files.add(mGifFile);
        mGifFile = getFileForResource(this, R.raw.stay_woke, imagesDir, "stay_woke.gif");
        files.add(mGifFile);
        mGifFile = getFileForResource(this, R.raw.swag, imagesDir, "swag.gif");
        files.add(mGifFile);
        mGifFile = getFileForResource(this, R.raw.wah_gwaan, imagesDir, "wah_gwaan.gif");
        files.add(mGifFile);
        mGifFile = getFileForResource(this, R.raw.werk, imagesDir, "werk.gif");
        files.add(mGifFile);
        mGifFile = getFileForResource(this, R.raw.word, imagesDir, "word.gif");
        files.add(mGifFile);
        mGifFile = getFileForResource(this, R.raw.wtf, imagesDir, "wtf.gif");
        files.add(mGifFile);
        mGifFile = getFileForResource(this, R.raw.cabron, imagesDir, "cabron.gif");
        files.add(mGifFile);
        mGifFile = getFileForResource(this, R.raw.da_haters, imagesDir, "da_haters.gif");
        files.add(mGifFile);
        mGifFile = getFileForResource(this, R.raw.habibti, imagesDir, "habibti.gif");
        files.add(mGifFile);
        mGifFile = getFileForResource(this, R.raw.khalas, imagesDir, "khalas.gif");
        files.add(mGifFile);
        mGifFile = getFileForResource(this, R.raw.please, imagesDir, "please.gif");
        files.add(mGifFile);
        mGifFile = getFileForResource(this, R.raw.pride, imagesDir, "pride.gif");
        files.add(mGifFile);
        mGifFile = getFileForResource(this, R.raw.ramadan, imagesDir, "ramadan.gif");
        files.add(mGifFile);
        mGifFile = getFileForResource(this, R.raw.ya_zool, imagesDir, "ya_zool.gif");
        files.add(mGifFile);


        mPngFile = getFileForResource(this, R.raw.dessert_android, imagesDir, "image.png");
        mWebpFile = getFileForResource(this, R.raw.animated_webp, imagesDir, "image.webp");
    }

    @Override
    public View onCreateInputView() {

        mGifButton = new Button(this);
        mGifButton.setText("Insert GIF");
        mGifButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                ImageKeyboard.this.doCommitContent("A waving flag", MIME_TYPE_GIF, mGifFile);
            }
        });

        mPngButton = new Button(this);
        mPngButton.setText("Insert PNG");
        mPngButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                ImageKeyboard.this.doCommitContent("A droid logo", MIME_TYPE_PNG, mPngFile);
            }
        });

        mWebpButton = new Button(this);
        mWebpButton.setText("Insert WebP");
        mWebpButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                ImageKeyboard.this.doCommitContent(
                        "Android N recovery animation", MIME_TYPE_WEBP, mWebpFile);
            }
        });

        final LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.addView(mGifButton);
        layout.addView(mPngButton);
        layout.addView(mWebpButton);



        View view = LayoutInflater.from(this).inflate(R.layout.keyboard, null, false);
        CircleIndicator indicator = (CircleIndicator) view.findViewById(R.id.indicator);
        AdapterPager adapterPager = new AdapterPager(this, files);
        ViewPager viewPager = (ViewPager) view.findViewById(R.id.pager);
        viewPager.setAdapter(adapterPager);
        indicator.setViewPager(viewPager);
        adapterPager.registerDataSetObserver(indicator.getDataSetObserver());
        adapterPager.setOnItemClickListener(this);
        return view;
    }

    @Override
    public boolean onEvaluateFullscreenMode() {
        // In full-screen mode the inserted content is likely to be hidden by the IME. Hence in this
        // sample we simply disable full-screen mode.
        return false;
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        mGifButton.setEnabled(mGifFile != null && isCommitContentSupported(info, MIME_TYPE_GIF));
        mPngButton.setEnabled(mPngFile != null && isCommitContentSupported(info, MIME_TYPE_PNG));
        mWebpButton.setEnabled(mWebpFile != null && isCommitContentSupported(info, MIME_TYPE_WEBP));
    }

    private static File getFileForResource(
            @NonNull Context context, @RawRes int res, @NonNull File outputDir,
            @NonNull String filename) {
        final File outputFile = new File(outputDir, filename);
        final byte[] buffer = new byte[4096];
        InputStream resourceReader = null;
        try {
            try {
                resourceReader = context.getResources().openRawResource(res);
                OutputStream dataWriter = null;
                try {
                    dataWriter = new FileOutputStream(outputFile);
                    while (true) {
                        final int numRead = resourceReader.read(buffer);
                        if (numRead <= 0) {
                            break;
                        }
                        dataWriter.write(buffer, 0, numRead);
                    }
                    return outputFile;
                } finally {
                    if (dataWriter != null) {
                        dataWriter.flush();
                        dataWriter.close();
                    }
                }
            } finally {
                if (resourceReader != null) {
                    resourceReader.close();
                }
            }
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public void onItemClick(int position, View v) {
        ImageKeyboard.this.doCommitContent("Hi...", MIME_TYPE_GIF, files.get(position));
    }
}
