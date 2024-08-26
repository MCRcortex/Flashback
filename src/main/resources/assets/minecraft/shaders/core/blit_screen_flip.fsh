#version 150

uniform sampler2D DiffuseSampler;

in vec2 texCoord;

out vec4 fragColor;

void main() {
    vec4 colour = texture(DiffuseSampler, texCoord);
    //TODO: turn into a matrix multiplication
    //float y = 0.299 * colour.r + 0.587 * colour.g + 0.114 * colour.b;
    //fragColor = vec4(y, (colour.b - y) * 0.565, (colour.r - y) * 0.713, colour.a);
    fragColor = colour;
}
