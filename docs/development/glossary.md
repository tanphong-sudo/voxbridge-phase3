# Glossary

## ONNX

Open format for representing machine learning models. In this project, ONNX is used as a portable model format between laptop tests and potential Android runtime integration.

## ONNX Runtime

Runtime engine that loads and runs ONNX model files.

## Execution Provider

Backend selected by ONNX Runtime to execute model operations on specific hardware or libraries, such as CPU, NNAPI, or QNN.

## QNN Execution Provider

Qualcomm path for ONNX Runtime to execute model graphs through Qualcomm AI software stack and Snapdragon acceleration where supported.

## Qualcomm AI Hub

Qualcomm platform providing pre-optimized models, Workbench tooling for compile/quantize/profile/validate, and sample apps/deployment references.

## Quantization

Reducing numerical precision of model weights/activations to improve size, speed, memory, and power usage, usually with some accuracy trade-off.

## SafetyFrame

Lightweight representation of safety-critical meaning extracted from source and translated text. It can capture action, equipment, object number, location, negation, hazard, urgency, and confidence. It is used for verification, not as the main translation mechanism.

## Slot Preservation

Ensuring critical command elements are preserved in the translated output.

## No-speech hallucination

Case where the speech recognizer produces text from silence or non-speech noise.
