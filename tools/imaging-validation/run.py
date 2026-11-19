import sys; import os; sys.path.insert(0,os.path.dirname(os.path.abspath(__file__)))
from scenes import *
import port2; detect=port2.detect2
W,H=1600,1200
results=[]
for name,bgf in [("dark desk",bg_dark),("light desk (white-on-light)",bg_light),("busy background",bg_busy)]:
    for t in range(12):
        cx,cy=W/2+rng.uniform(-150,150),H/2+rng.uniform(-80,80); sw,sh=rng.uniform(380,520),rng.uniform(470,560)
        jit=lambda: rng.uniform(-60,60)
        q=[(cx-sw+jit(),cy-sh+jit()),(cx+sw+jit(),cy-sh+jit()),(cx+sw+jit(),cy+sh+jit()),(cx-sw+jit(),cy+sh+jit())]
        q=[(min(max(x,5),W-5),min(max(y,5),H-5)) for x,y in q]
        img=scene(bgf(),q,shadow=t%2==0)
        g=cv2.cvtColor(img,cv2.COLOR_RGB2GRAY)
        d=detect(g); gt=[(x/W,y/H) for x,y in q]
        results.append((name,iou(d,gt) if d else 0.0))
        if t==0: cv2.imwrite(f"out/scene_{name.split()[0]}.jpg",cv2.cvtColor(img,cv2.COLOR_RGB2BGR))
import collections
by=collections.defaultdict(list)
for n,v in results: by[n].append(v)
for n,v in by.items(): print(f"{n:32s} pass(IoU>=0.9) {sum(x>=0.9 for x in v)}/{len(v)}  mean IoU {np.mean(v):.3f}  min {min(v):.3f}")
tot=[v for _,v in results]; print("overall", sum(x>=0.9 for x in tot),"/",len(tot))
