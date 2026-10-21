package chat.dumber.app;
/** JNI ABI shared with dumber-chat-core. Keep this class name when shrinking. */
public final class NativeCore {
    static { System.loadLibrary("dumber_chat_core"); }
    public static native String invoke(String request);
    private NativeCore() {}
}
