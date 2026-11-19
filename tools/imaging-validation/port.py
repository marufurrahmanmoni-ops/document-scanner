# Faithful Python port of EdgeDetector.kt + FilterEngine.kt + ImageOps.warp for algorithm validation.
import cv2, numpy as np, math
WORK=480.0; MIN_AREA=0.15
def median(g): return float(np.median(g))
def canny_edges(src):
    m=median(src); lo=max(0,0.66*m); up=max(min(255,1.33*m), lo+30)
    e=cv2.Canny(src,lo,up); return cv2.dilate(e, cv2.getStructuringElement(cv2.MORPH_RECT,(3,3)))
def grad_edges(src):
    k=cv2.getStructuringElement(cv2.MORPH_RECT,(5,5))
    g=cv2.morphologyEx(src,cv2.MORPH_GRADIENT,k)
    _,b=cv2.threshold(g,0,255,cv2.THRESH_BINARY|cv2.THRESH_OTSU)
    return cv2.morphologyEx(b,cv2.MORPH_CLOSE,k)
def order(pts):
    tl=min(pts,key=lambda p:p[0]+p[1]); br=max(pts,key=lambda p:p[0]+p[1])
    tr=min(pts,key=lambda p:p[1]-p[0]); bl=max(pts,key=lambda p:p[1]-p[0]); return [tl,tr,br,bl]
def angles_ok(q):
    for i in range(4):
        a,b,c=q[(i+3)%4],q[i],q[(i+1)%4]
        v1=(a[0]-b[0],a[1]-b[1]); v2=(c[0]-b[0],c[1]-b[1]); n=math.hypot(*v1)*math.hypot(*v2)
        if n<1e-6: return False
        d=math.degrees(math.acos(max(-1,min(1,(v1[0]*v2[0]+v1[1]*v2[1])/n))))
        if d<45 or d>135: return False
    return True
def area(q):
    s=0
    for i in range(4): a=q[i];b=q[(i+1)%4]; s+=a[0]*b[1]-b[0]*a[1]
    return abs(s)/2
def quads(edges,w,h):
    cs,_=cv2.findContours(edges,cv2.RETR_LIST,cv2.CHAIN_APPROX_SIMPLE); out=[]
    for c in cs:
        if cv2.contourArea(c)<w*h*MIN_AREA: continue
        c2=c.astype(np.float32); ap=cv2.approxPolyDP(c2,0.02*cv2.arcLength(c2,True),True)
        if len(ap)!=4: continue
        if not cv2.isContourConvex(ap.astype(np.int32)): continue
        q=order([(p[0][0]/w,p[0][1]/h) for p in ap])
        if angles_ok(q): out.append(q)
    return out
def score(q):
    d=lambda a,b: math.hypot(a[0]-b[0],a[1]-b[1])
    t,bm,l,r=d(q[0],q[1]),d(q[3],q[2]),d(q[0],q[3]),d(q[1],q[2])
    return area(q)-0.1*(abs(t-bm)/max(t,bm)+abs(l-r)/max(l,r))
def detect(gray):
    s=WORK/max(gray.shape)
    small=cv2.resize(gray,(int(gray.shape[1]*s),int(gray.shape[0]*s)),interpolation=cv2.INTER_AREA) if s<1 else gray.copy()
    b=cv2.GaussianBlur(small,(5,5),0); h,w=small.shape
    cands=quads(canny_edges(b),w,h)+quads(grad_edges(b),w,h)
    return max(cands,key=score) if cands else None
def flatten(g):
    s=min(512/max(g.shape),1.0); sm=cv2.resize(g,(int(g.shape[1]*s),int(g.shape[0]*s)),interpolation=cv2.INTER_AREA)
    k=max((max(sm.shape)//25)|1,9); sm=cv2.morphologyEx(sm,cv2.MORPH_CLOSE,cv2.getStructuringElement(cv2.MORPH_ELLIPSE,(k,k)))
    sm=cv2.medianBlur(sm,21); bg=cv2.resize(sm,(g.shape[1],g.shape[0]),interpolation=cv2.INTER_LINEAR)
    return cv2.divide(g,bg,scale=255)
def stretch(g,lo=0.01,hi=0.99):
    hist=np.bincount(g.ravel(),minlength=256).cumsum()/g.size
    low=int(np.argmax(hist>=lo)); high=int(np.argmax(hist>=hi))
    if high-low<10: return g.copy()
    a=255.0/(high-low); return cv2.convertScaleAbs(g,alpha=a,beta=-low*a)
def unsharp(s): b=cv2.GaussianBlur(s,(0,0),1.5); return cv2.addWeighted(s,1.5,b,-0.5,0)
def clean(rgb):
    lab=cv2.cvtColor(rgb,cv2.COLOR_RGB2Lab); L,a,b=cv2.split(lab)
    L=unsharp(stretch(flatten(L)))
    a=cv2.convertScaleAbs(a.astype(np.float32)*1.15-128*0.15); b=cv2.convertScaleAbs(b.astype(np.float32)*1.15-128*0.15)
    return cv2.cvtColor(cv2.merge([L,a,b]),cv2.COLOR_Lab2RGB)
def gray(rgb): return stretch(flatten(cv2.cvtColor(rgb,cv2.COLOR_RGB2GRAY)))
def bw(rgb,soft=False):
    f=flatten(cv2.cvtColor(rgb,cv2.COLOR_RGB2GRAY)); w=max(f.shape)
    blk=min(max((w//(20 if soft else 40))|1,15),255)
    o=cv2.adaptiveThreshold(f,255,cv2.ADAPTIVE_THRESH_GAUSSIAN_C,cv2.THRESH_BINARY,blk,6 if soft else 12)
    return o if soft else cv2.medianBlur(o,3)
def colourfulness(rgb):
    s=min(256/max(rgb.shape[:2]),1); sm=cv2.resize(rgb,(int(rgb.shape[1]*s),int(rgb.shape[0]*s))).astype(np.float32)
    R,G,B=sm[...,0],sm[...,1],sm[...,2]; rg=R-G; yb=0.5*(R+G)-B
    return math.sqrt(rg.std()**2+yb.std()**2)+0.3*math.sqrt(rg.mean()**2+yb.mean()**2)
def warp(src,q,maxe):
    h,w=src.shape[:2]; p=[(x*w,y*h) for x,y in q]; d=lambda a,b: math.hypot(a[0]-b[0],a[1]-b[1])
    ow=max(d(p[0],p[1]),d(p[3],p[2])); oh=max(d(p[0],p[3]),d(p[1],p[2])); s=min(maxe/max(ow,oh),1.0); ow*=s; oh*=s
    M=cv2.getPerspectiveTransform(np.float32(p),np.float32([(0,0),(ow-1,0),(ow-1,oh-1),(0,oh-1)]))
    return cv2.warpPerspective(src,M,(round(ow),round(oh)),flags=cv2.INTER_AREA if s<0.5 else cv2.INTER_CUBIC,borderMode=cv2.BORDER_REPLICATE)
