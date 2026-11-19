import sys,time; import os; sys.path.insert(0,os.path.dirname(os.path.abspath(__file__)))
from scenes import *
import port2
W,H=1600,1200
def bg_white(W=1600,H=1200): b=np.zeros((H,W,3),np.uint8); b[:]=(228,226,222); return np.clip(b+rng.normal(0,4,b.shape),0,255).astype(np.uint8)
ok=0;n=0
for t in range(12):
    cx,cy=W/2,H/2; q=[(cx-450+rng.uniform(-60,60),cy-520+rng.uniform(-40,40)),(cx+450+rng.uniform(-60,60),cy-520+rng.uniform(-40,40)),(cx+450,cy+540),(cx-450,cy+540)]
    q=[(min(max(x,5),W-5),min(max(y,5),H-5)) for x,y in q]
    img=scene(bg_white(),q,shadow=True,blur=2.0,noise=8)
    d=port2.detect2(cv2.cvtColor(img,cv2.COLOR_RGB2GRAY)); v=iou(d,[(x/W,y/H) for x,y in q]) if d else 0
    ok+=v>=0.9; n+=1
print("near-white desk, heavy blur:",ok,"/",n)
# false positive: no page at all
fp=sum(port2.detect2(cv2.cvtColor(bg_busy(),cv2.COLOR_RGB2GRAY)) is not None for _ in range(10))
print("false detections on empty busy background:",fp,"/10")
# timing at analysis resolution 640x480
img=cv2.resize(scene(bg_light(),[(300,150),(1300,180),(1350,1100),(250,1050)]),(640,480)); g=cv2.cvtColor(img,cv2.COLOR_RGB2GRAY)
t0=time.time(); [port2.detect2(g) for _ in range(50)]; print("ms per 640x480 frame (desktop CPU, Python):", round((time.time()-t0)/50*1000,1))
