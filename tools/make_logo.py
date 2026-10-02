import sys, random
from PIL import Image, ImageDraw
TIER=[(157,157,157),(188,111,73),(211,171,54),(78,62,67),(150,101,206)]
STEEL=(150,156,166); AMBER=(255,176,40); CYAN=(70,205,255); RED=(240,64,80)
def shade(c,f): return tuple(max(0,min(255,int(v*f))) for v in c)
def lerp(a,b,t): return tuple(int(a[i]+(b[i]-a[i])*t) for i in range(3))

# ---------- isometric voxel pipe ----------
s=2
def P(x,y,z,ox,oy): return (ox+(x-y)*2*s, oy+(x+y)*s-z*2*s)
def build():
    V={}
    def box(x0,x1,y0,y1,z0,z1,c):
        for x in range(x0,x1+1):
            for y in range(y0,y1+1):
                for z in range(z0,z1+1): V[(x,y,z)]={'c':c}
    box(0,4,0,4,0,4,STEEL)
    box(5,15,1,3,1,3,STEEL); box(1,3,5,15,1,3,STEEL)
    for x,c in ((7,TIER[1]),(10,TIER[2])): box(x,x,0,4,0,4,c)
    for y,c in ((7,TIER[3]),(10,TIER[4])): box(0,4,y,y,0,4,c)
    box(16,16,0,4,0,4,TIER[4]); box(0,4,16,16,0,4,TIER[2])
    def mouth(key,rim,center,col):
        for a in (1,2,3):
            for b in (1,2,3):
                p=rim(a,b); V[p][key]=(30,33,44); V[p]['emit_'+key]=1
        p=rim(2,2); V[p][key]=col; V[p]['emit_'+key]=1
    mouth('right',lambda a,b:(16,a,b),None,CYAN)
    mouth('left',lambda a,b:(a,16,b),None,RED)
    mouth('top',lambda a,b:(a,b,4),None,AMBER)
    return V
def face(d,pts,col,rng,noise=7):
    d.polygon(pts,fill=col)
def render(V, size=(96,96)):
    W,H=size; ox,oy=W//2-0*s, H//2+2*s
    img=Image.new("RGBA",(W,H),(0,0,0,0)); d=ImageDraw.Draw(img)
    rng=random.Random(7)
    for (x,y,z) in sorted(V,key=lambda k:(k[0]+k[1]+k[2],k[2])):
        v=V[(x,y,z)]; c=v['c']
        top=[P(x,y,z+1,ox,oy),P(x+1,y,z+1,ox,oy),P(x+1,y+1,z+1,ox,oy),P(x,y+1,z+1,ox,oy)]
        right=[P(x+1,y,z,ox,oy),P(x+1,y+1,z,ox,oy),P(x+1,y+1,z+1,ox,oy),P(x+1,y,z+1,ox,oy)]
        left=[P(x,y+1,z,ox,oy),P(x+1,y+1,z,ox,oy),P(x+1,y+1,z+1,ox,oy),P(x,y+1,z+1,ox,oy)]
        # voxel faces are only drawn when exposed
        def hidden(dx,dy,dz): return (x+dx,y+dy,z+dz) in V and (V[(x+dx,y+dy,z+dz)]['c']==c or True)
        if not hidden(0,0,1): d.polygon(top,fill=v['top'] if v.get('emit_top') else shade(c,1.18))
        if not hidden(1,0,0): d.polygon(right,fill=v['right'] if v.get('emit_right') else shade(c,.62))
        if not hidden(0,1,0): d.polygon(left,fill=v['left'] if v.get('emit_left') else shade(c,.84))
    # texture jitter + bevel
    px=img.load()
    for yy in range(H):
        for xx in range(W):
            r,g,b,a=px[xx,yy]
            if a: 
                j=rng.choice((-9,-4,0,0,0,4,9)); px[xx,yy]=(max(0,min(255,r+j)),max(0,min(255,g+j)),max(0,min(255,b+j)),255)
    return img
def outline(img,color=(8,8,10,255),th=1):
    W,H=img.size; out=Image.new("RGBA",(W+2*th,H+2*th),(0,0,0,0)); out.paste(img,(th,th))
    a=out.split()[3]; src=a.load(); res=out.copy(); rp=res.load(); op=out.load()
    for y in range(out.height):
        for x in range(out.width):
            if src[x,y]==0:
                for dy in range(-th,th+1):
                    for dx in range(-th,th+1):
                        xx,yy=x+dx,y+dy
                        if 0<=xx<out.width and 0<=yy<out.height and src[xx,yy]: rp[x,y]=color; break
                    else: continue
                    break
    return res
def crop(img):
    return img.crop(img.getbbox())

# ---------- pixel font ----------
G={
'U':["X...X"]*5+["X...X",".XXX."],
'N':["X...X","XX..X","XX..X","X.X.X","X..XX","X..XX","X...X"],
'I':["XXXXX","..X..","..X..","..X..","..X..","..X..","XXXXX"],
'V':["X...X"]*4+["X...X",".X.X.","..X.."],
'E':["XXXXX","X....","X....","XXXX.","X....","X....","XXXXX"],
'R':["XXXX.","X...X","X...X","XXXX.","X.X..","X..X.","X...X"],
'S':[".XXX.","X...X","X....",".XXX.","....X","X...X",".XXX."],
'A':[".XXX.","X...X","X...X","XXXXX","X...X","X...X","X...X"],
'L':["X...."]*6+["XXXXX"],
'P':["XXXX.","X...X","X...X","XXXX.","X....","X....","X...."],
}
def word(text,k,colors=None,gap=1):
    cols=[]; 
    w=len(text)*6+(len(text)-1)*gap; h=7
    grid=[[None]*w for _ in range(h)]
    x0=0
    for i,ch in enumerate(text):
        for r,row in enumerate(G[ch]):
            for c,v in enumerate(row):
                if v=='X':
                    for dc in (0,1): grid[r][x0+c+dc]=i
        x0+=6+gap
    W,H=w*k,h*k
    img=Image.new("RGBA",(W,H),(0,0,0,0)); px=img.load(); rng=random.Random(3)
    base=(150,152,158)
    t=k//4 if k>=8 else 1
    for r in range(h):
        for c in range(w):
            i=grid[r][c]
            if i is None: continue
            col=colors[i] if colors else base
            up=r>0 and grid[r-1][c] is not None; dn=r<h-1 and grid[r+1][c] is not None
            lf=c>0 and grid[r][c-1] is not None; rt=c<w-1 and grid[r][c+1] is not None
            for yy in range(k):
                for xx in range(k):
                    f=1.0+rng.choice((-.06,-.03,0,0,.03,.06))
                    if not up and yy<t: f+=.28
                    if not lf and xx<t: f+=.2
                    if not dn and yy>=k-t: f-=.34
                    if not rt and xx>=k-t: f-=.26
                    # hairline cracks
                    if (r*7+c*13+yy*3+xx*5)%41==0 and rng.random()<.5: f-=.18
                    px[c*k+xx,r*k+yy]=shade(col,f)+(255,)
    return img
def stack(img,bevel=1): return img
def banner(H=600):
    V=build(); icon=crop(outline(render(V,(200,160)),th=1))
    sc=H//icon.height
    icon=icon.resize((icon.width*sc,icon.height*sc),Image.NEAREST)
    k1=12; k2=22
    t1=outline_big(word("UNIVERSAL",k1),k1)
    t2=outline_big(word("PIPES",k2,colors=TIER),k2)
    gapx=40; gapy=20
    W=icon.width+gapx+max(t1.width,t2.width)+10
    Hh=max(icon.height,t1.height+gapy+t2.height)
    out=Image.new("RGBA",(W,Hh),(0,0,0,0))
    out.alpha_composite(icon,(0,(Hh-icon.height)//2))
    ty=(Hh-(t1.height+gapy+t2.height))//2
    out.alpha_composite(t1,(icon.width+gapx,ty))
    out.alpha_composite(t2,(icon.width+gapx,ty+t1.height+gapy))
    return out
def outline_big(img,k):
    th=max(2,k//3)
    # shadow + outline at pixel level
    W,H=img.size; o=Image.new("RGBA",(W+2*th+k//2,H+2*th+k//2),(0,0,0,0))
    a=img.split()[3]
    dark=Image.new("RGBA",img.size,(8,8,10,255))
    from PIL import ImageFilter
    grow=a.filter(ImageFilter.MaxFilter(2*th+1))
    sh=Image.new("RGBA",img.size,(0,0,0,110))
    o.paste(sh,(th+k//2,th+k//2),grow)
    o.paste(dark,(th,th),grow)
    o.alpha_composite(img,(th,th))
    return o
if __name__=="__main__":
    out=sys.argv[1]
    b=banner(); b.save(out+"/banner.png")
    V=build(); ic=crop(outline(render(V,(200,160)),th=1))
    sc=448//max(ic.size); ic=ic.resize((ic.width*sc,ic.height*sc),Image.NEAREST)
    sq=Image.new("RGBA",(512,512),(0,0,0,0)); sq.alpha_composite(ic,((512-ic.width)//2,(512-ic.height)//2)); sq.save(out+"/logo_512.png")
    big=sq.resize((1024,1024),Image.NEAREST); big.save(out+"/logo.png")
