"""Validate device tensor inventories before computing graph-parity metrics."""
def align_outputs(names, outputs):
    if len(outputs) != len(names):
        raise ValueError('Output count differs from source graph')
    if set(names) == set(outputs):
        return [outputs[name] for name in names]
    numbered = [f'output_{i}' for i in range(len(names))]
    if set(numbered) == set(outputs):
        return [outputs[name] for name in numbered]
    raise ValueError('Unrecognized output names; explicit tensor mapping required')
