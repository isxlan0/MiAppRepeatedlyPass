package io.github.isxlan0.miappxspace;

import java.nio.charset.StandardCharsets;
import java.util.*;
import static io.github.isxlan0.miappxspace.DexModel.*;

/** Resource identities from resources.arsc, independent of assigned numeric IDs. */
final class ResourceTable {
    final Map<Integer,String> names=new HashMap<>();final Map<String,Integer> ids=new HashMap<>();
    ResourceTable(){}
    static List<String> pool(byte[] b,int p){
        need(u2(b,p)==1&&u2(b,p+2)>=28,"Invalid resource string pool");int count=u4(b,p+8),flags=i4(b,p+16),start=u4(b,p+20),end=at((long)p+u4(b,p+4));range(b,p+u2(b,p+2),count*4L);List<String> out=new ArrayList<>();
        for(int i=0;i<count;i++){int at=at((long)p+start+u4(b,p+u2(b,p+2)+4*i));need(at<end,"Bad pool offset");
            if((flags&256)!=0){int[] cursor={at};length8(b,cursor);int n=length8(b,cursor);need(cursor[0]+(long)n<end&&b[cursor[0]+n]==0,"Bad UTF8 resource string");out.add(new String(b,cursor[0],n,StandardCharsets.UTF_8));}
            else {int n=u2(b,at);at+=2;if((n&0x8000)!=0){n=((n&0x7fff)<<16)|u2(b,at);at+=2;}need(at+n*2L+2<=end&&u2(b,at+n*2)==0,"Bad UTF16 resource string");out.add(new String(b,at,n*2,StandardCharsets.UTF_16LE));}
        }return out;
    }
    static int length8(byte[] b,int[] p){range(b,p[0],1);int n=b[p[0]++]&255;if((n&128)!=0){range(b,p[0],1);n=((n&127)<<8)|(b[p[0]++]&255);}return n;}
    ResourceTable(byte[] b){
        range(b,0,12);need(u2(b,0)==2&&u4(b,4)==b.length,"Invalid resource table");
        for(int p=u2(b,2);p<b.length;){int size=u4(b,p+4);need(size>=u2(b,p+2)&&size>=8,"Invalid resource chunk");range(b,p,size);
            if(u2(b,p)==0x200){need(u2(b,p+2)>=284,"Invalid resource package");int pkg=u4(b,p+8);need(pkg>0&&pkg<256,"Bad resource package ID");int chars=0;while(chars<128&&u2(b,p+12+chars*2)!=0)chars++;String packageName=new String(b,p+12,chars*2,StandardCharsets.UTF_16LE);
                List<String> types=pool(b,p+u4(b,p+268)),keys=pool(b,p+u4(b,p+276));int typeOffset=u2(b,p+2)>=288?u4(b,p+284):0;
                for(int q=p+u2(b,p+2);q<p+size;){int len=u4(b,q+4),header=u2(b,q+2);need(len>=header&&header>=8&&len<=p+size-q,"Bad package chunk");
                    if(u2(b,q)==0x201){need(header>=20,"Bad resource type");int type=b[q+8]&255,flags=b[q+9]&255,count=u4(b,q+12),entries=u4(b,q+16);need(type>0&&type<=types.size()&&type+typeOffset<256&&count<=65536,"Bad resource entry table");
                        int step=(flags&2)!=0?2:4;range(b,q+header,(long)count*step);need(header+(long)count*step<=entries&&entries<=len,"Overlapping resource entries");
                        for(int i=0;i<count;i++){int index=i,off;if((flags&1)!=0){need(step==4,"Unsupported sparse resource offsets");index=u2(b,q+header+i*4);off=u2(b,q+header+i*4+2)*4;}else if(step==2){int n=u2(b,q+header+i*2);if(n==0xffff)continue;off=n*4;}else {off=i4(b,q+header+i*4);if(off==-1)continue;need(off>=0,"Bad resource offset");}
                            int entry=at((long)q+entries+off);need(entry+8L<=q+len,"Truncated resource entry");int key=u4(b,entry+4);need(key<keys.size(),"Bad resource name");String name=packageName+":"+types.get(type-1)+"/"+keys.get(key);int id=(pkg<<24)|((type+typeOffset)<<16)|index;
                            String previous=names.put(id,name);need(previous==null||previous.equals(name),"Ambiguous resource ID");Integer old=ids.put(name,id);need(old==null||old==id,"Ambiguous resource name");
                        }
                    }q+=len;
                }
            }p+=size;
        }
    }
}
