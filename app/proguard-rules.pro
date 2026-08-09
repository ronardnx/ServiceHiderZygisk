-keep class com.n0hindi.servicehider.zygisk.ZygoteEntry {
    public static void premain();
    public static void main();
}
-keep class com.n0hindi.servicehider.zygisk.ServiceManagerProxy { *; }
