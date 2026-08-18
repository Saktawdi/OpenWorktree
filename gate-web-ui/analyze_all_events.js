const fs = require('fs');
const harPath = 'D:/360MoveData/Users/17428/Desktop/lib/127.0.0.1.har';
const data = JSON.parse(fs.readFileSync(harPath, 'utf8'));

console.log('Total entries:', data.log.entries.length);

data.log.entries.forEach((e, idx) => {
  const req = e.request;
  const res = e.response;
  console.log(`\n================ ENTRY ${idx} ================`);
  console.log(`${req.method} ${req.url} -> ${res.status}`);
  if (req.postData) {
    console.log('Req PostData:', req.postData.text);
  }
  const text = res.content.text || '';
  if (text.startsWith('event:') || text.startsWith('data:') || res.content.mimeType.includes('event-stream')) {
    // parse sse
    const lines = text.split('\n');
    let evCount = 0;
    for (let i = 0; i < lines.length; i++) {
      if (lines[i].startsWith('data:')) {
        evCount++;
        const rawData = lines[i].slice(5).trim();
        try {
          const parsed = JSON.parse(rawData);
          console.log(`  [SSE Data ${evCount}]`, JSON.stringify(parsed, null, 2));
        } catch (err) {
          console.log(`  [SSE Data ${evCount} (raw)]`, rawData);
        }
      }
    }
  } else {
    console.log('Response text:', text);
  }
});
