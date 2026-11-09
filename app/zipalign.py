#!/usr/bin/env python3
# Minimal zipalign replacement: 4-byte align uncompressed entries (STORED),
# keep resources.arsc and already-stored entries STORED, pad the local-header
# extra field so STORED data begins on a 4-byte boundary.
import sys, struct, zlib

def u16(b,o): return struct.unpack_from('<H',b,o)[0]
def u32(b,o): return struct.unpack_from('<I',b,o)[0]

def align(src, dst, alignment=4):
    with open(src,'rb') as f: data=f.read()
    # find End Of Central Directory
    eocd=data.rfind(b'PK\x05\x06')
    if eocd<0: raise SystemExit("no EOCD")
    total=u16(data,eocd+10)
    cdoff=u32(data,eocd+16)

    out=bytearray()
    entries=[]  # (new_local_offset, central_dir_record_bytes, cd_fixup_offset_in_record)
    p=cdoff
    # read central directory first to know names/flags
    cds=[]
    for _ in range(total):
        assert data[p:p+4]==b'PK\x01\x02'
        method=u16(data,p+10)
        csize=u32(data,p+20)
        nlen=u16(data,p+28); elen=u16(data,p+30); clen=u16(data,p+32)
        lho=u32(data,p+42)
        name=data[p+46:p+46+nlen]
        rec=bytearray(data[p:p+46+nlen+elen+clen])
        cds.append({'method':method,'csize':csize,'lho':lho,'name':name,'rec':rec})
        p+=46+nlen+elen+clen

    for cd in cds:
        lho=cd['lho']
        assert data[lho:lho+4]==b'PK\x03\x04', "bad local header"
        method=u16(data,lho+8)
        csize=u32(data,lho+18)
        nlen=u16(data,lho+26); elen=u16(data,lho+28)
        name=data[lho+30:lho+30+nlen]
        hdr=bytearray(data[lho:lho+30+nlen])      # fixed header + name
        extra=bytearray(data[lho+30+nlen:lho+30+nlen+elen])
        body=data[lho+30+nlen+elen:lho+30+nlen+elen+csize]

        new_off=len(out)
        if method==0:  # STORED -> align data start
            data_start=new_off+30+nlen+len(extra)
            pad=(-data_start)%alignment
            if pad: extra+=b'\x00'*pad
            struct.pack_into('<H',hdr,28,len(extra))
        # update this entry's central-dir local-header offset
        struct.pack_into('<I',cd['rec'],42,new_off)
        out+=hdr; out+=extra; out+=body

    new_cdoff=len(out)
    for cd in cds: out+=cd['rec']
    cdsize=len(out)-new_cdoff
    eocd_rec=bytearray(data[eocd:eocd+22])
    struct.pack_into('<I',eocd_rec,12,cdsize)
    struct.pack_into('<I',eocd_rec,16,new_cdoff)
    out+=eocd_rec
    with open(dst,'wb') as f: f.write(out)

    # verify STORED data offsets
    bad=0
    verify=out
    e2=verify.rfind(b'PK\x05\x06'); c2=u32(verify,e2+16); n2=u16(verify,e2+10); q=c2
    for _ in range(n2):
        method=u16(verify,q+10); lho=u32(verify,q+42)
        nlen=u16(verify,q+28); elen=u16(verify,q+30); clen=u16(verify,q+32)
        if method==0:
            ln=u16(verify,lho+26); le=u16(verify,lho+28)
            ds=lho+30+ln+le
            if ds%alignment!=0: bad+=1
        q+=46+nlen+elen+clen
    print("aligned, misaligned STORED entries:",bad)
    if bad: raise SystemExit("alignment failed")

if __name__=='__main__':
    align(sys.argv[1],sys.argv[2])
