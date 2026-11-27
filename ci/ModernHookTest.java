package io.github.isxlan0.miappxspace;

import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.*;
import java.util.*;

/** Exercise actual adapter semantics with a deterministic official-API Chain. */
public final class ModernHookTest {
    static int original(int value){return value+1;}
    static class Chain implements XposedInterface.Chain {
        final Object[] args={2};int calls;Throwable failure;Object[] passed;
        public Executable getExecutable(){try{return ModernHookTest.class.getDeclaredMethod("original",int.class);}catch(Exception e){throw new AssertionError(e);}}
        public Object getThisObject(){return null;}
        public List<Object> getArgs(){return Collections.unmodifiableList(Arrays.asList(args));}
        public Object getArg(int index){return args[index];}
        public Object proceed()throws Throwable{return proceed(args);}
        public Object proceed(Object[] supplied)throws Throwable{calls++;passed=supplied.clone();if(failure!=null)throw failure;return original((Integer)supplied[0]);}
        public Object proceedWith(Object receiver)throws Throwable{return proceed();}
        public Object proceedWith(Object receiver,Object[] supplied)throws Throwable{return proceed(supplied);}
    }
    static void check(boolean value,String reason){if(!value)throw new AssertionError(reason);}
    public static void main(String[] args)throws Throwable {
        Hooks.api=(XposedInterface)Proxy.newProxyInstance(ModernHookTest.class.getClassLoader(),new Class[]{XposedInterface.class},(p,m,a)->null);
        Hooks.begin();
        try{Hooks.commit();throw new AssertionError("empty installation reported success");}
        catch(IllegalStateException expected){Hooks.rollback();}
        Chain normal=new Chain();
        Object value=Hooks.intercept(normal,new HookCallback(){
            protected void beforeHookedMethod(Param p){p.args[0]=10;p.setObjectExtra("local",23);}
            protected void afterHookedMethod(Param p){check(p.getObjectExtra("local").equals(23),"per-call extras lost");p.setResult((Integer)p.getResult()*2);}
        });
        check(value.equals(22)&&normal.calls==1&&normal.args[0].equals(2),"modified arguments/after result");
        Chain shorted=new Chain();
        check(Hooks.intercept(shorted,new HookCallback(){protected void beforeHookedMethod(Param p){p.setResult(44);}}).equals(44)&&shorted.calls==0,"short circuit called original");
        Chain refused=new Chain();SecurityException denial=new SecurityException("refused");
        try{Hooks.intercept(refused,new HookCallback(){protected void beforeHookedMethod(Param p){p.setThrowable(denial);}});throw new AssertionError("denial swallowed");}
        catch(SecurityException expected){check(expected==denial&&refused.calls==0,"denial identity");}
        Chain originalFailure=new Chain();originalFailure.failure=new IllegalArgumentException("original");
        try{Hooks.intercept(originalFailure,new HookCallback(){});throw new AssertionError("original failure swallowed");}
        catch(IllegalArgumentException expected){check(expected==originalFailure.failure&&originalFailure.calls==1,"original failure repeated");}
        Chain beforeBug=new Chain();
        check(Hooks.intercept(beforeBug,new HookCallback(){protected void beforeHookedMethod(Param p){throw new IllegalStateException("hook bug");}}).equals(3)&&beforeBug.calls==1,"before failure changed original");
        Chain afterBug=new Chain();
        check(Hooks.intercept(afterBug,new HookCallback(){protected void afterHookedMethod(Param p){p.setResult(99);throw new IllegalStateException("after bug");}}).equals(3)&&afterBug.calls==1,"after failure changed original");
        Chain nested=new Chain();
        check(Hooks.intercept(nested,new HookCallback(){protected void beforeHookedMethod(Param p)throws Throwable {
            p.setObjectExtra("local",1);Hooks.intercept(new Chain(),new HookCallback(){protected void beforeHookedMethod(Param q){q.setObjectExtra("local",2);}});
            check(p.getObjectExtra("local").equals(1),"nested invocation leaks extras");
        }}).equals(3),"nested call failed");
        System.out.println("PASS: modern Hook argument/result/exception/early-return/nesting behavior");
    }
}
