package com.dennis.pixelrepair;

/**
 * Experimental local chroma and shadow noise repair. NOT generative AI.
 * Reads only the original source array; writes edits to a separate output array.
 * This makes the result independent of traversal order and preserves originals.
 */
public final class ShadowRepairEngine {
    private ShadowRepairEngine() {}

    /**
     * Run after v0.1 defect repair. Never override isolated-defect corrections.
     * @param deep false = Balanced; true = Shadow Lab.
     * @return exact number of newly edited pixels.
     */
    public static int denoise(int[] src, int[] out, int width, int height,
                              int sensitivity, boolean deep) {
        if (src == null || out == null || src.length != out.length ||
            width < 3 || height < 3 || (long)width*height != src.length ||
            sensitivity <= 0) return 0;
        int s=Math.max(0,Math.min(100,sensitivity));
        final int limit=deep?138:115;
        final int range=deep?26:26;
        final int deltaY=deep?16:15;
        final int[] off={-width-1,-width,-width+1,-1,1,width-1,width,width+1};
        int count=0;
        for(int y=2;y<height-2;y++){
            for(int x=2;x<width-2;x++){
                int i=y*width+x, p=src[i];
                if ((p>>>24)!=255 || out[i]!=p) continue;
                int r=(p>>>16)&255, g=(p>>>8)&255, b=p&255;
                int lum=luma(r,g,b);
                if(lum >= limit) continue;
                int min=lum,max=lum,sumR=0,sumB=0,weightSum=0;
                boolean opaque=true;
                for(int j=0;j<8;j++){
                    int q=src[i+off[j]];
                    if((q>>>24)!=255){opaque=false;break;}
                    int nr=(q>>>16)&255,ng=(q>>>8)&255,nb=q&255;
                    int ny=luma(nr,ng,nb);
                    min=Math.min(min,ny);max=Math.max(max,ny);
                    int diffY=Math.abs(ny-lum);
                    if(diffY <= deltaY){
                        int wt=diffY<=7?2:1;
                        sumR+=wt*(nr-ny);
                        sumB+=wt*(nb-ny);
                        weightSum+=wt;
                    }
                }
                if(!opaque || max-min>range || weightSum<8) continue;
                int deltaRC=Math.round((float)sumR/weightSum)-(r-lum);
                int deltaBC=Math.round((float)sumB/weightSum)-(b-lum);
                if(Math.abs(deltaRC)+Math.abs(deltaBC)<(deep?3:5)) continue;
                if(Math.max(Math.abs(deltaRC),Math.abs(deltaBC))>(deep?26:21)) continue;
                // Critical texture protection: inspect outer 5x5 perimeter.
                // Trees, grass, roof shingles, small edges and contrasting colours
                // have structured variation; do not treat it as sensor noise.
                int minOuter=255,maxOuter=0,lowRC=512,highRC=-512,
                    lowBC=512,highBC=-512;
                boolean structured=false;
                for(int ry=-2;ry<=2 && !structured;ry++){
                    for(int rx=-2;rx<=2;rx++){
                        if(Math.abs(ry)<2 && Math.abs(rx)<2) continue;
                        int q=src[i+ry*width+rx];
                        if((q>>>24)!=255){structured=true;break;}
                        int nr=(q>>>16)&255,ng=(q>>>8)&255,nb=q&255;
                        int ny=luma(nr,ng,nb);
                        int rc=nr-ny,bc=nb-ny;
                        minOuter=Math.min(minOuter,ny);
                        maxOuter=Math.max(maxOuter,ny);
                        lowRC=Math.min(lowRC,rc); highRC=Math.max(highRC,rc);
                        lowBC=Math.min(lowBC,bc); highBC=Math.max(highBC,bc);
                        if(maxOuter-minOuter>(deep?22:20) ||
                           highRC-lowRC>(deep?25:21) ||
                           highBC-lowBC>(deep?25:21)){
                            structured=true;break;
                        }
                    }
                }
                if(structured || lum<minOuter-11 || lum>maxOuter+11) continue;
                double shadow=Math.min(1.0,Math.max(0.0,(limit-lum)/52.0));
                double strength=(deep?(0.50+0.004*s):(0.19+0.0027*s))*shadow;
                int dr=(int)Math.round(deltaRC*strength);
                int db=(int)Math.round(deltaBC*strength);
                // Keep weighted luminance approximately constant.
                int dg=(int)Math.round((-77.0*dr-29.0*db)/150.0);
                // v0.3 does not smooth luminance at all. Chroma-only changes
                // greatly reduce the risk of erasing fine detail.
                int rr=clamp(r+dr),gg=clamp(g+dg),bb=clamp(b+db);
                int edited=(0xff<<24)|(rr<<16)|(gg<<8)|bb;
                if(edited!=p){out[i]=edited;count++;}
            }
        }
        return count;
    }

    private static int luma(int r,int g,int b){
        return (77*r+150*g+29*b)>>8;
    }
    private static int clamp(int v){
        return Math.max(0,Math.min(255,v));
    }
}
