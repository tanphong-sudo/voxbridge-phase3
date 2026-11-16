"""Deduplicate byte-identical ONNX initializers without changing graph operators or precision."""
import hashlib
import gc
from pathlib import Path
import onnx
from onnx import external_data_helper


def initializers(graph):
    yield from graph.initializer
    for node in graph.node:
        for attr in node.attribute:
            if attr.type == onnx.AttributeProto.GRAPH:
                yield from initializers(attr.g)
            elif attr.type == onnx.AttributeProto.GRAPHS:
                for child in attr.graphs:
                    yield from initializers(child)


def share_weights(source: Path, destination: Path):
    destination.mkdir(parents=True, exist_ok=False)
    seen = {}
    total = 0
    with (destination/'shared_weights.bin').open('wb') as shared:
        for graph_path in sorted(source.glob('*.onnx')):
            model = onnx.load(graph_path, load_external_data=True)
            fingerprints = []
            for tensor in initializers(model.graph):
                # The exporter uses raw_data for large weights. Leave small/typed constants
                # embedded rather than changing their representation or operator attributes.
                if not tensor.HasField('raw_data') or len(tensor.raw_data) < 1024:
                    continue
                data = tensor.raw_data
                digest = hashlib.sha256(data).digest()
                fingerprints.append((tensor.name, digest))
                total += len(data)
                key = (digest, len(data))
                if key not in seen:
                    seen[key] = shared.tell()
                    shared.write(data)
                external_data_helper.set_external_data(tensor, 'shared_weights.bin',
                                                       offset=seen[key], length=len(data))
                tensor.ClearField('raw_data')
            out = destination/graph_path.name
            onnx.save_model(model, out)
            shared.flush()
            # Do not retain the source graph or final raw tensor while loading its
            # verification copy. One graph at a time bounds the exporter lifetime.
            del model
            if 'tensor' in locals():
                del tensor
            if 'data' in locals():
                del data
            gc.collect()
            onnx.checker.check_model(str(out))
            restored = onnx.load(out, load_external_data=True)
            restored_hashes = {t.name: hashlib.sha256(t.raw_data).digest()
                               for t in initializers(restored.graph) if t.HasField('raw_data')}
            for name, digest in fingerprints:
                if restored_hashes.get(name) != digest:
                    raise RuntimeError(f'Weight round-trip differs: {graph_path.name}/{name}')
            del restored
            gc.collect()
    used = (destination/'shared_weights.bin').stat().st_size
    print(f'ONNX shared weights: {total:,} -> {used:,} bytes; exact SHA-256 round-trip verified', flush=True)
