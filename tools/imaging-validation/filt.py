import sys; import os; sys.path.insert(0,os.path.dirname(os.path.abspath(__file__)))
from scenes import *; import port2
W,H=1600,1200; q=[(380,120),(1250,170),(1330,1120),(300,1060)]
img=scene(bg_light(),q,shadow=True,color_ink=True)
d=port2.detect2(cv2.cvtColor(img,cv2.COLOR_RGB2GRAY))
up=warp(img,d,1400)
outs=[("Original",up),("Clean",clean(up)),("Gray",cv2.cvtColor(gray(up),cv2.COLOR_GRAY2RGB)),("B&W",cv2.cvtColor(bw(up),cv2.COLOR_GRAY2RGB))]
print("auto picks:", "gray" if colourfulness(up)<8 else "clean", round(colourfulness(up),1))
th=300; tiles=[]
for n,o in outs:
    t=cv2.resize(o,(th,int(o.shape[0]*th/o.shape[1]))); t=cv2.copyMakeBorder(t,40,10,10,10,cv2.BORDER_CONSTANT,value=(235,238,243))
    cv2.putText(t,n,(14,28),cv2.FONT_HERSHEY_SIMPLEX,0.8,(17,24,39),2); tiles.append(t)
hmax=max(t.shape[0] for t in tiles); tiles=[cv2.copyMakeBorder(t,0,hmax-t.shape[0],0,0,cv2.BORDER_CONSTANT,value=(235,238,243)) for t in tiles]
src=cv2.resize(img,(int(img.shape[1]*hmax/img.shape[0]),hmax)); 
pts=np.int32([(x*src.shape[1],y*src.shape[0]) for x,y in d]); cv2.polylines(src,[pts],True,(61,214,140),4)
row=np.hstack([src]+tiles); cv2.imwrite('out/pipeline.jpg',cv2.cvtColor(row,cv2.COLOR_RGB2BGR),[cv2.IMWRITE_JPEG_QUALITY,85])
# quantitative: paper whiteness & ink darkness after clean under the shadow gradient
c=clean(up); g=cv2.cvtColor(c,cv2.COLOR_RGB2GRAY)
left=g[:, :g.shape[1]//5]; right=g[:, -g.shape[1]//5:]
print("paper brightness left/right (shadowed vs lit), clean:", int(np.percentile(left,90)), int(np.percentile(right,90)))
gu=cv2.cvtColor(up,cv2.COLOR_RGB2GRAY); print("before:", int(np.percentile(gu[:, :gu.shape[1]//5],90)), int(np.percentile(gu[:, -gu.shape[1]//5:],90)))
