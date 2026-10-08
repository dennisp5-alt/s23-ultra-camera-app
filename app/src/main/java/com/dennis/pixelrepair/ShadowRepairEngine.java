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
        final int limit=deep?166:115;
        final int range=deep?45:31;
        final int deltaY=deep?24:15;
        final int[] off={-width-1,-width,-width+1,-1,1,width-1,width,width+1};
        int count=0;
        for(int y=1;y<height-1;y++){
            for(int x=1;x<width-1;x++){
                int i=y*width+x, p=src[i];
                if ((p>>>24)!=255 || out[i]!=p) continue;
                int r=(p>>>16)&255, g=(p>>>8)&255, b=p&255;
                int lum=luma(r,g,b);
                if(lum >= limit) continue;
                int min=lum,max=lum,sumR=0,sumB=0,sumY=0,weightSum=0;
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
                        sumY+=wt*ny;
                        weightSum+=wt;
                    }
                }
                if(!opaque || max-min>range || weightSum<8) continue;
                int deltaRC=Math.round((float)sumR/weightSum)-(r-lum);
                int deltaBC=Math.round((float)sumB/weightSum)-(b-lum);
                if(Math.abs(deltaRC)+Math.abs(deltaBC)<(deep?3:5)) continue;
                if(Math.max(Math.abs(deltaRC),Math.abs(deltaBC))>(deep?32:21)) continue;
                double shadow=Math.min(1.0,Math.max(0.0,(limit-lum)/52.0));
                double strength=(deep?(0.50+0.004*s):(0.19+0.0027*s))*shadow;
                int dr=(int)Math.round(deltaRC*strength);
                int db=(int)Math.round(deltaBC*strength);
                // Keep weighted luminance approximately constant.
                int dg=(int)Math.round((-77.0*dr-29.0*db)/150.0);
                if(deep && lum<76 && max-min<=13){
                    int avgY=sumY/weightSum;
                    if(Math.abs(avgY-lum)>2){
                        int lumCorrection=(int)Math.round((avgY-lum)*(0.10+0.0015*s));
                        dr+=lumCorrection;dg+=lumCorrection;db+=lumCorrection;
                    }
                }
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
