package io.github.isxlan0.miappxspace;
public final class ActivationStatusTest {
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    public static void main(String[] args){
        ActivationStatus state=ActivationStatus.evaluate(true);
        check(state.healthy&&!state.problem&&state.title.equals("✓ 正常运行中"),"binding must immediately mean normal");
        check(state.detail.isEmpty(),"connected app still shows a verification checklist");
        state=ActivationStatus.evaluate(false);
        check(!state.healthy&&state.problem&&state.title.equals("未连接 LSPosed"),"disconnection must clear normal status");
        check(ActivationStatus.evaluate(true).healthy,"reconnection must recover without system reports");
        System.out.println("PASS: LSPosed connection alone controls app status; disconnect/reconnect and no pending checks");
    }
}
