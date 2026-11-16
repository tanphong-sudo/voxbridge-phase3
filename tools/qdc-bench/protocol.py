"""Lossless tensor transfer; QNN KV caches stay on the phone."""
import base64,zipfile
from pathlib import Path
import numpy as np
DTYPES={'float32':np.dtype('<f4'),'int32':np.dtype('<i4')}
def encode_tensor(array):
    array=np.asarray(array)
    if array.dtype.name not in DTYPES:raise ValueError('Unsupported dtype '+str(array.dtype))
    array=np.ascontiguousarray(array,dtype=DTYPES[array.dtype.name])
    return dict(dtype=array.dtype.name,shape=list(array.shape),data=base64.b64encode(array.tobytes()).decode('ascii'))
def decode_tensor(item):
    dtype=DTYPES[item['dtype']];shape=tuple(item['shape'])
    if any(not isinstance(n,int) or n<0 for n in shape):raise ValueError('Invalid tensor shape')
    data=base64.b64decode(item['data'],validate=True)
    if len(data)!=int(np.prod(shape))*dtype.itemsize:raise ValueError('Tensor byte count mismatch')
    return np.frombuffer(data,dtype=dtype).reshape(shape).copy()
def safe_extract(file,dest):
    dest=Path(dest).resolve()
    with zipfile.ZipFile(file) as z:
        for info in z.infolist():
            target=(dest/info.filename).resolve()
            if not target.is_relative_to(dest) or (info.external_attr>>16)&0o170000==0o120000:raise ValueError('Unsafe model archive entry')
        z.extractall(dest)
