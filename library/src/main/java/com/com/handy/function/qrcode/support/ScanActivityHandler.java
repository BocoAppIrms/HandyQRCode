/*
 * Copyright (C) 2008 ZXing authors
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

package com.com.handy.function.qrcode.support;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Handler;
import android.os.Message;
import android.provider.Browser;

import com.com.handy.function.qrcode.ScanSingleActivity;
import com.com.handy.function.qrcode.support.camera.CameraManager;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.DecodeHintType;
import com.google.zxing.Result;
import com.handy.function.qrcode.R;

import java.util.Collection;
import java.util.Map;

/**
 * This class handles all the messaging which comprises the state machine for handy_activity_scan_single.
 *
 * @author dswitkin@google.com (Daniel Switkin)
 */
public final class ScanActivityHandler extends Handler {

    private final ScanSingleActivity activity;
    private final DecodeThread decodeThread;
    private final CameraManager cameraManager;
    private State state;

    public ScanActivityHandler(ScanSingleActivity activity, Collection<BarcodeFormat> decodeFormats, Map<DecodeHintType, ?> baseHints, String characterSet, CameraManager cameraManager) {
        this.activity = activity;
        decodeThread = new DecodeThread(activity, decodeFormats, baseHints, characterSet, new ViewfinderResultPointCallback(activity.getViewfinderView()));
        decodeThread.start();
        state = State.SUCCESS;

        // Start ourselves capturing previews and decoding.
        this.cameraManager = cameraManager;
        cameraManager.startPreview();
        restartPreviewAndDecode();
    }

    @Override
    public void handleMessage(Message message) {
        if (message.what == R.id.handy_qrcode_restart_preview) {
            restartPreviewAndDecode();

        } else if (message.what == R.id.handy_qrcode_decode_succeeded) {
            state = State.SUCCESS;
            activity.handleDecode((Result) message.obj);

        } else if (message.what == R.id.handy_qrcode_decode_failed) {
            // We're decoding as fast as possible, so when one decode fails, start another.
            state = State.PREVIEW;
            cameraManager.requestPreviewFrame(decodeThread.getHandler(), R.id.handy_qrcode_decode);

        } else if (message.what == R.id.handy_qrcode_return_scan_result) {
            activity.setResult(Activity.RESULT_OK, (Intent) message.obj);
            activity.finish();

        } else if (message.what == R.id.handy_qrcode_launch_product_query) {
            String url = (String) message.obj;

            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.addFlags(Intents.FLAG_NEW_DOC);
            intent.setData(Uri.parse(url));

            ResolveInfo resolveInfo = activity.getPackageManager().resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
            String browserPackageName = null;
            if (resolveInfo != null && resolveInfo.activityInfo != null) {
                browserPackageName = resolveInfo.activityInfo.packageName;
            }

            // Needed for default Android browser / Chrome only apparently
            if (browserPackageName != null) {
                if ("com.android.browser".equals(browserPackageName) || "com.android.chrome".equals(browserPackageName)) {
                    intent.setPackage(browserPackageName);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    intent.putExtra(Browser.EXTRA_APPLICATION_ID, browserPackageName);
                }
            }

            try {
                activity.startActivity(intent);
            } catch (ActivityNotFoundException ignored) {
                ignored.printStackTrace();
            }
        }
    }

    public void quitSynchronously() {
        state = State.DONE;
        cameraManager.stopPreview();
        Message quit = Message.obtain(decodeThread.getHandler(), R.id.handy_qrcode_quit);
        quit.sendToTarget();
        try {
            // Wait at most half a second; should be enough time, and onPause() will timeout quickly
            decodeThread.join(500L);
        } catch (InterruptedException e) {
            // continue
        }

        // Be absolutely sure we don't send any queued up messages
        removeMessages(R.id.handy_qrcode_decode_succeeded);
        removeMessages(R.id.handy_qrcode_decode_failed);
    }

    // TODO: 2018/5/28 重新扫描二维码
    private void restartPreviewAndDecode() {
        if (state == State.SUCCESS) {
            state = State.PREVIEW;
            cameraManager.requestPreviewFrame(decodeThread.getHandler(), R.id.handy_qrcode_decode);
            activity.drawViewfinder();
        }
    }

    private enum State {
        PREVIEW,
        SUCCESS,
        DONE
    }
}
