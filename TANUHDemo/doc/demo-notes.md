# Demo notes

1. Start with app storage cleared to prove the APK contains no models.
2. Open the app while offline and show the graceful network-policy message.
3. Connect to Wi-Fi and tap **Prepare models**.
4. Record a clearly positive or negative English memo to demonstrate the
   MobileBERT classifier, for example: "The release went really well and I am
   very happy."
5. Show the transcript, classifier result, and on-device pipeline latency.
6. Restart the app and show that the models are immediately reused.
7. Point to the remote manifest and explain that changing a version triggers an
   OTA update without publishing a new APK.
8. Filter Logcat by `ModelManager`, `ConnectivityPolicy`, `VoiceMemoActivity`,
   and `MemoSentimentClassifier` to show each OTA and inference stage.
