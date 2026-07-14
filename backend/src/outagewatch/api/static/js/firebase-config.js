/*
 * OutageWatch web push configuration.
 *
 * Browser alerts stay OFF until you fill in the three placeholders below and
 * set OW_PUSH_ENABLED = true. Everything else on the site works without this.
 *
 * How to get the values (about two minutes, one time):
 *   1. Firebase console  ->  Project settings  ->  "Your apps"  ->  Add app  ->  Web.
 *      Register an app named "OutageWatch Web". Copy `apiKey` and `appId` from
 *      the config it shows you into the fields below.
 *   2. Firebase console  ->  Project settings  ->  Cloud Messaging  ->
 *      "Web Push certificates"  ->  Generate key pair. Copy the public key
 *      string into OW_VAPID_KEY.
 *   3. Set OW_PUSH_ENABLED = true and redeploy.
 *
 * These values are public by design (they ship in every web client). Access is
 * governed by FCM and the backend, not by keeping them secret.
 */
window.OW_FIREBASE = {
  apiKey: "PASTE_WEB_API_KEY",
  authDomain: "outagewatch.firebaseapp.com",
  projectId: "outagewatch",
  storageBucket: "outagewatch.firebasestorage.app",
  messagingSenderId: "468206169285",
  appId: "PASTE_WEB_APP_ID",
};
window.OW_VAPID_KEY = "PASTE_VAPID_PUBLIC_KEY";
window.OW_PUSH_ENABLED = false;
