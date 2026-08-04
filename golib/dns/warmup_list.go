package dns

// DefaultWarmupDomains 默认预热域名列表
// 包含中国大陆用户常用但可能无法直接访问的域名
var DefaultWarmupDomains = []string{
	// Google 服务
	"www.google.com",
	"mail.google.com",
	"google.com",

	// YouTube
	"www.youtube.com",
	"youtube.com",

	// Twitter/X
	"www.twitter.com",
	"twitter.com",
	"x.com",

	// Facebook
	"www.facebook.com",
	"facebook.com",

	// Instagram
	"www.instagram.com",
	"instagram.com",

	// GitHub
	"github.com",
	"api.github.com",

	// Wikipedia
	"www.wikipedia.org",
	"en.wikipedia.org",

	// Reddit
	"www.reddit.com",
	"reddit.com",

	// OpenAI
	"chat.openai.com",
	"api.openai.com",

	// Cloudflare (CDN)
	"www.cloudflare.com",

	// Netflix
	"www.netflix.com",
	"netflix.com",

	// Spotify
	"www.spotify.com",
	"spotify.com",

	// Amazon AWS
	"aws.amazon.com",

	// Microsoft
	"www.microsoft.com",
	"microsoft.com",

	// Apple
	"www.apple.com",
	"apple.com",

	// Telegram
	"web.telegram.org",
	"telegram.org",
}
