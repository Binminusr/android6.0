/**
 * Copyright (C) 2010,2011 Thundersoft Corporation
 * All rights Reserved
 */
precision mediump float;
varying vec2 vTextureCoord;
uniform sampler2D texture;

float getY(vec4 color){
   float y = 0.299*color.x+0.587*color.y+0.114*color.z;
   return y;
}

void main() {
   vec4 color = texture2D(texture, vTextureCoord);
   float y = getY(color);
   gl_FragColor = vec4(1.0-y,1.0-y,1.0-y,color.a);
}