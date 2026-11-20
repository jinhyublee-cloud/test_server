# 로그 호출 제거 (릴리스에서 어떤 내용도 로그로 남기지 않음)
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
}
