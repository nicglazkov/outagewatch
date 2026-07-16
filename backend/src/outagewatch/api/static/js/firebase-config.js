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
  apiKey: "AIzaSyCygllgB-gpT3i-HziyRJFYpagq-yNlSEg",
  authDomain: "outagewatch.firebaseapp.com",
  projectId: "outagewatch",
  storageBucket: "outagewatch.firebasestorage.app",
  messagingSenderId: "468206169285",
  appId: "1:468206169285:web:9c95356bf92a9d3ab994ac",
};
// The one value that can only come from the Firebase console:
// Project settings -> Cloud Messaging -> Web Push certificates -> Generate key
// pair, then paste the public key here and flip OW_PUSH_ENABLED to true.
window.OW_VAPID_KEY = "BLKM-hLgIklv-so3kR8Ghotyw962o7HUt_f80G_5o5mmtWXThxEbaTCrqK60wslR62DgM9GaAyW-Z29YdamgCzs";
window.OW_PUSH_ENABLED = true;
