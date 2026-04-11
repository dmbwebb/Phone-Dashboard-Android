# Play Store Rejection - April 10, 2026

**App**: Mi Ritmo Digital (com.miritmodigital.app)  
**Account**: Multilink Colombia  
**Status**: Rejected  
**Version code**: 93

---

## Issue 1: Invalid Account Deletion Link

**Policy**: User Data - Account Deletion Requirement

> You have declared that your app does allow users to create an account however the deletion link you provided to users is invalid.
>
> Account Deletion Link in the designated field in the Data safety form is broken.

**Details**: The URL `https://www.miritmodigital.com/privacy` returns HTTP 404 (Not Found).

**Root cause**: GitHub Pages SPA routing — the React app handles `/privacy` client-side via JavaScript redirect (404.html), but HTTP clients/crawlers receive a 404 status code before any JS executes.

**Fix**: Add a post-build step to copy `dist/index.html` to `dist/privacy/index.html` (and other SPA routes) so GitHub Pages serves them with HTTP 200. Then redeploy.

---

## Issue 2: Invalid Encryption Declaration

**Policy**: Data Safety Section - Data encrypted in transit

> You have declared that user data is encrypted in transit in your app's Data safety form, and we've detected unencrypted network traffic that may carry user data off device.

**Details**: Version code 93 communicates with `http://143.198.99.126` (plain HTTP, not HTTPS).

All 6 server endpoints in `strings.xml` use `http://`:
- `http://143.198.99.126/data/app-config.json`
- `http://143.198.99.126/study/config.json`
- `http://143.198.99.126/study/enroll-email.json`
- `http://143.198.99.126/study/deactivate/%s.json`
- `http://143.198.99.126/study/activate/%s.json`
- `http://143.198.99.126/study/latest-version.json`

Additionally, `network_security_config.xml` explicitly allows cleartext traffic for `143.198.99.126`.

**Fix options**:
1. **Preferred**: Set up HTTPS/TLS on the DigitalOcean server (requires domain name for Let's Encrypt), update Android app URLs to `https://`, update `network_security_config.xml` to disallow cleartext, rebuild app.
2. **Quick (temporary)**: Update Data Safety form to declare data is NOT encrypted in transit.

---

## Resolution Checklist

- [ ] Fix GitHub Pages SPA routing so `/privacy` returns HTTP 200
- [ ] Redeploy website
- [ ] Verify `https://www.miritmodigital.com/privacy` returns 200
- [ ] Set up HTTPS on DigitalOcean server
- [ ] Update Android app server URLs from `http://` to `https://`
- [ ] Update `network_security_config.xml` to disallow cleartext for production
- [ ] Update Data Safety form encryption declaration if needed
- [ ] Rebuild and resubmit app to Play Console
