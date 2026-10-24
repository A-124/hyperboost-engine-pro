# Firebase feedback (optional)

HyperBoost can send user-initiated feedback through Firebase Authentication (anonymous) and Cloud Firestore. This is optional: the public source deliberately does **not** include the maintainer's `app/google-services.json`, `.firebaserc`, or Firebase project details.

Without a Firebase configuration the app still builds and runs. The feedback form reports `Firebase is not configured. Add app/google-services.json.` instead of sending anything.

## Enable feedback in your own build

1. Create a Firebase project in the [Firebase console](https://console.firebase.google.com/).
2. Add an Android app with the package name `com.aistudio.hyperboost.mkvxyz`.
3. Enable **Authentication → Sign-in method → Anonymous**.
4. Create a **Cloud Firestore** database in production mode.
5. Download `google-services.json` and place it at `app/google-services.json`. This file is git-ignored.
6. Deploy the included security rules from the repository root:

   ```bash
   npx firebase-tools login
   npx firebase-tools use --add        # creates .firebaserc locally; it is git-ignored
   npx firebase-tools deploy --only firestore:rules
   ```

7. Rebuild the app.

If `google-services.json` is missing, the Gradle Google Services plugin is configured to warn and continue (`googleServices.missing.passthrough=true` in `gradle.properties`).

## Data stored

Feedback is written to the `feedback` collection with the category, message, app version, Android version, device model, server timestamp, and `new` status. The included rules allow authenticated submissions only and deny client reads, updates, and deletes.

## Security

Never commit service-account keys, admin credentials, or signing material. The Firebase Android client configuration contains project identifiers rather than server secrets, but it is still kept out of this repository so each contributor uses their own project. Keep your Firestore rules reviewed and deployed whenever they change.
