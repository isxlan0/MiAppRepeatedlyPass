package io.github.isxlan0.miappxspace;

/** Fixed identities; gaps never renumber another application's data. */
final class CloneSlots {
    static final int STOCK=999, SECOND=998, THIRD=997, MAX_CLONES=3;
    static final int[] USERS={STOCK,SECOND,THIRD};
    static boolean contains(int[] ids,int target){for(int id:ids)if(id==target)return true;return false;}
    static int count(int[] ids){int count=0;for(int id:ids)if(id!=0)count++;return count;}
    static boolean allowed(int id){return id==STOCK||id==SECOND||id==THIRD;}
    static int next(int[] ids){
        if(count(ids)>=MAX_CLONES)throw new IllegalStateException("每个应用最多 3 个分身，连同主应用共 4 个；请先删除不需要的分身。");
        for(int id:USERS)if(!contains(ids,id))return id;
        throw new IllegalStateException("没有可用的分身位置。");
    }
    static void requireCreate(int[] ids,int id){
        if(!allowed(id))throw new IllegalArgumentException("只能创建系统 999 和模块 998、997 三个分身。");
        if(contains(ids,id))throw new IllegalStateException("这个分身已经存在。");
        int next=next(ids);
        if(id!=next)throw new IllegalStateException("请先创建应用"+(1001-next)+"（空间 "+next+"）。");
    }
    static String label(CharSequence base,int id){return base.toString()+(id==0?"":Integer.toString(1001-id));}
    static String description(int id){return id==0?"主应用":id==STOCK?"系统分身":"分身 "+(1000-id);}
}
