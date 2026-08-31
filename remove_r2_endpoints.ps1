$content = Get-Content "D:\Project\apk-pulse-stream_Android\pulsestream\cloudflare-index-watch-together-v2.js" -Raw

# Remove /update/publish endpoint block
$pattern1 = '(?s)else if \(`n      url\.pathname === "/update/publish" &&`n      request\.method === "POST"`n    \) \{.*?^\s*\}\s*$'
$content = $content -replace $pattern1, ''

# Remove /update/download endpoint block  
$pattern2 = '(?s)else if \(`n      url\.pathname === "/update/download" &&`n      request\.method === "GET"`n    \) \{.*?^\s*\}\s*$'
$content = $content -replace $pattern2, ''

Set-Content "D:\Project\apk-pulse-stream_Android\pulsestream\cloudflare-index-watch-together-v2.js" $content
Write-Host "Done!"