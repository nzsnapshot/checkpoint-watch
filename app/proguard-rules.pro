# Add project specific ProGuard rules here.

# WorkManager instantiates Workers by reflection (Class.forName + a (Context, WorkerParameters)
# constructor) using the class name stored in its work database — it does not know about
# ScanWorker at compile time, so R8 has no other reason to keep it. Without this rule a release
# build silently drops or renames the constructor and every background scan crashes at runtime.
-keep class nz.personal.checkpointwatch.scan.ScanWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
