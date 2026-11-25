package io.github.isxlan0.miappxspace;

/** Verify creation constraints remain enforced after removing the release-version gate. */
public final class CloneSlotsTest {
    static void check(boolean condition){if(!condition)throw new AssertionError();}
    static void rejects(int[] ids,int target){
        try{CloneSlots.requireCreate(ids,target);}
        catch(IllegalArgumentException|IllegalStateException expected){return;}
        throw new AssertionError("Unexpected allowed creation: "+target);
    }
    public static void main(String[] args){
        check(CloneSlots.next(new int[]{0})==999);
        check(CloneSlots.next(new int[]{0,999})==998);
        check(CloneSlots.next(new int[]{0,999,998})==997);
        CloneSlots.requireCreate(new int[]{0},999);
        CloneSlots.requireCreate(new int[]{0,999},998);
        CloneSlots.requireCreate(new int[]{0,999,998},997);
        rejects(new int[]{0},998);
        rejects(new int[]{0,999},997);
        rejects(new int[]{0,999},999);
        rejects(new int[]{0,999,998,997},997);
        rejects(new int[]{0,999,996,995},998);
        rejects(new int[]{0,999},996);
        check(CloneSlots.next(new int[]{0,999,997})==998);
        check("App4".equals(CloneSlots.label("App",997)));
        System.out.println("PASS: fixed order, three-clone quota including legacy clones, duplicate/invalid target rejection, stable identities");
    }
}
