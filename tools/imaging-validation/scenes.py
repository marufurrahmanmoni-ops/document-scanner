import cv2, numpy as np, random, math, sys
import os; sys.path.insert(0,os.path.dirname(os.path.abspath(__file__))); from port import *
rng=np.random.default_rng(1)
def page(w=850,h=1100,color_ink=False):
    p=np.full((h,w,3),245,np.uint8)
    cv2.putText(p,"INVOICE 2026",(60,110),cv2.FONT_HERSHEY_SIMPLEX,2.2,(30,30,30),5)
    for i in range(22):
        y=200+i*38; x2=int(780-rng.integers(0,260))
        cv2.line(p,(60,y),(x2,y),(60,60,60),6)
    if color_ink: cv2.circle(p,(650,950),70,(200,40,40),8); cv2.putText(p,"PAID",(600,965),cv2.FONT_HERSHEY_SIMPLEX,1.3,(200,40,40),4)
    return p
def scene(bg,quad_px,W=1600,H=1200,shadow=True,noise=6,blur=1.2,color_ink=False):
    pg=page(color_ink=color_ink); h,w=pg.shape[:2]
    M=cv2.getPerspectiveTransform(np.float32([(0,0),(w,0),(w,h),(0,h)]),np.float32(quad_px))
    out=bg.copy(); warped=cv2.warpPerspective(pg,M,(W,H)); mask=cv2.warpPerspective(np.full((h,w),255,np.uint8),M,(W,H))
    out[mask>0]=warped[mask>0]
    if shadow:
        g=np.tile(np.linspace(0.6,1.05,W),(H,1)).astype(np.float32)[...,None]; out=np.clip(out*g,0,255).astype(np.uint8)
    out=np.clip(out.astype(np.int16)+rng.normal(0,noise,out.shape),0,255).astype(np.uint8)
    return cv2.GaussianBlur(out,(0,0),blur)
def bg_dark(W=1600,H=1200): b=np.zeros((H,W,3),np.uint8); b[:]=(70,50,35); return np.clip(b+rng.normal(0,12,b.shape),0,255).astype(np.uint8)
def bg_light(W=1600,H=1200): b=np.zeros((H,W,3),np.uint8); b[:]=(205,200,190); return np.clip(b+rng.normal(0,5,b.shape),0,255).astype(np.uint8)
def bg_busy(W=1600,H=1200):
    b=bg_dark(W,H)
    for i in range(40): cv2.line(b,(int(rng.integers(0,W)),int(rng.integers(0,H))),(int(rng.integers(0,W)),int(rng.integers(0,H))),(int(rng.integers(0,255)),)*3,int(rng.integers(1,6)))
    return b
def iou(q1,q2,W=400,H=300):
    a=np.zeros((H,W),np.uint8); b=a.copy()
    cv2.fillPoly(a,[np.int32([(x*W,y*H) for x,y in q1])],1); cv2.fillPoly(b,[np.int32([(x*W,y*H) for x,y in q2])],1)
    return (a&b).sum()/max((a|b).sum(),1)
