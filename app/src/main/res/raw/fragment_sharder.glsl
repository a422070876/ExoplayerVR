#extension GL_OES_EGL_image_external : require
varying highp vec2 vTexCoord;
uniform samplerExternalOES sTexture;
uniform highp mat4 uSTMatrix;
void main() {
    highp vec2 tx_transformed = (uSTMatrix * vec4(vTexCoord, 0, 1.0)).xy;
    gl_FragColor = texture2D(sTexture, tx_transformed);
}