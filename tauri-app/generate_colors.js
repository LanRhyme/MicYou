import { themeFromSourceColor, argbFromHex } from "@material/material-color-utilities";

function argbToHslString(argb) {
  const r = (argb >> 16) & 255;
  const g = (argb >> 8) & 255;
  const b = argb & 255;
  
  const rf = r / 255;
  const gf = g / 255;
  const bf = b / 255;
  const max = Math.max(rf, gf, bf), min = Math.min(rf, gf, bf);
  let h, s, l = (max + min) / 2;

  if(max == min){
      h = s = 0; // achromatic
  }else{
      var d = max - min;
      s = l > 0.5 ? d / (2 - max - min) : d / (max + min);
      switch(max){
          case rf: h = (gf - bf) / d + (gf < bf ? 6 : 0); break;
          case gf: h = (bf - rf) / d + 2; break;
          case bf: h = (rf - gf) / d + 4; break;
      }
      h /= 6;
  }
  return `${Math.round(h * 360)} ${Math.round(s * 100)}% ${Math.round(l * 100)}%`;
}

const theme = themeFromSourceColor(argbFromHex("#4A672D"));
const sLight = theme.schemes.light.toJSON();
const sDark = theme.schemes.dark.toJSON();

console.log(":root {");
for(let key in sLight) {
  let kebab = key.replace(/([a-z0-9]|(?=[A-Z]))([A-Z])/g, '$1-$2').toLowerCase();
  console.log(`    --${kebab}: ${argbToHslString(sLight[key])};`);
}
console.log("}\n.dark {");
for(let key in sDark) {
  let kebab = key.replace(/([a-z0-9]|(?=[A-Z]))([A-Z])/g, '$1-$2').toLowerCase();
  console.log(`    --${kebab}: ${argbToHslString(sDark[key])};`);
}
console.log("}");
