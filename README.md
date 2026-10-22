# MACS STOP Dialer (Android)

SIP softphone for Android with the MACS STOP design. Calls use UDP, TCP or TLS (like MicroSIP) through the Linphone SDK.

## Build the APK on GitHub

1. Create a new repository on github.com (for example `macs-dialer-android`).
2. Upload **everything inside this folder**, including the hidden `.github` folder.
   Easiest way: on the repo page choose **Add file → Upload files** and drag all files and folders in.
   If the `.github` folder doesn't upload, create the file `.github/workflows/build-apk.yml` by hand
   (**Add file → Create new file**) and paste its contents.
3. Open the **Actions** tab. The **Build APK** job starts on its own (or press **Run workflow**).
4. When it shows a green tick (about 5–10 minutes), open the run and download **MACS-STOP-Dialer-APK** under *Artifacts*.
5. Unzip it and install `app-debug.apk` on the phone (allow *Install unknown apps*).

## First run on the phone

1. Allow **Microphone** and **Notifications**, and allow the app to ignore battery optimization so calls ring in the background.
2. Log in (james / eric / logan / nancy / mark, password 0007).
3. Settings → enter the SIP username, password, domain, choose **Transport** (UDP / TCP / TLS) and port if needed → **Save & Register**.
4. A green dot on the Calls screen means you're registered.

## Updating the design

The screens live in `app/src/main/assets/www/index.html`. Replace that file and push; GitHub builds a new APK.

## Note

The Linphone SDK is licensed under GPLv3. Using the app for yourself and your team is fine; selling or publishing
the app requires a commercial Linphone license.
