from port import *
import port
def canny_multi(src):
    outs=[]
    # Thresholds from gradient magnitude percentiles (robust to overall brightness), plus fixed low pairs.
    gx=cv2.Sobel(src,cv2.CV_32F,1,0,ksize=3); gy=cv2.Sobel(src,cv2.CV_32F,0,1,ksize=3)
    mag=cv2.magnitude(gx,gy); hi=float(np.percentile(mag,90)); hi=max(hi,30.0)
    pairs=[(0.4*hi,hi),(20,60)]
    k=cv2.getStructuringElement(cv2.MORPH_RECT,(3,3))
    for lo,up in pairs:
        e=cv2.Canny(src,lo,up); outs.append(cv2.dilate(e,k))
    return outs
def detect2(gray):
    s=WORK/max(gray.shape)
    small=cv2.resize(gray,(int(gray.shape[1]*s),int(gray.shape[0]*s)),interpolation=cv2.INTER_AREA) if s<1 else gray.copy()
    b=cv2.GaussianBlur(small,(5,5),0); h,w=small.shape
    cands=[]
    for e in canny_multi(b)+[grad_edges(b)]: cands+=quads(e,w,h)
    return max(cands,key=score) if cands else None
port.detect=detect2
def pct_hist(mag,p):
    bins=np.bincount(np.clip((mag/2).astype(int),0,511).ravel(),minlength=512).cumsum()
    return float(np.argmax(bins>=int(mag.size*p))*2)
_orig=canny_multi
def canny_multi(src):
    gx=cv2.Sobel(src,cv2.CV_32F,1,0,ksize=3); gy=cv2.Sobel(src,cv2.CV_32F,0,1,ksize=3)
    hi=max(pct_hist(cv2.magnitude(gx,gy),0.90),30.0); k=cv2.getStructuringElement(cv2.MORPH_RECT,(3,3))
    return [cv2.dilate(cv2.Canny(src,lo,up),k) for lo,up in [(0.4*hi,hi),(20,60)]]
