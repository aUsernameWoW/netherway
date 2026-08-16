package i18n

import (
	"reflect"
	"regexp"
	"sort"
	"strings"
	"testing"
)

// fmt 动词（跳过 %%），含 %[n] 位置前缀与旗标/宽度。
var verbRe = regexp.MustCompile(`%(?:\[\d+\])?[-+# 0]*(?:\d+|\*)?(?:\.(?:\d+|\*)?)?[a-zA-Z]`)

func verbsOf(format string) []string {
	all := verbRe.FindAllString(strings.ReplaceAll(format, "%%", ""), -1)
	if all == nil {
		return []string{}
	}
	return all
}

// en/zh 的动词必须一一对应：非位置动词按出现顺序比对（Sprintf 按顺序吃参数，
// 顺序错就是运行期错位）；用了位置动词的条目改比集合（顺序由 %[n] 决定）。
func TestCatalogVerbParity(t *testing.T) {
	for key, e := range catalog {
		en, zh := verbsOf(e[EN]), verbsOf(e[ZH])
		positional := strings.Contains(e[EN], "%[") || strings.Contains(e[ZH], "%[")
		a, b := append([]string{}, en...), append([]string{}, zh...)
		if positional {
			sort.Strings(a)
			sort.Strings(b)
		}
		if !reflect.DeepEqual(a, b) {
			t.Errorf("%s: 动词不一致 en=%v zh=%v", key, en, zh)
		}
	}
}

func TestCatalogEnHasNoCjk(t *testing.T) {
	for key, e := range catalog {
		for _, r := range e[EN] {
			if r >= 0x4E00 && r <= 0x9FFF {
				t.Errorf("%s: en 文案含中文", key)
				break
			}
		}
	}
}

func TestCatalogEntriesNonEmpty(t *testing.T) {
	if len(catalog) < 60 {
		t.Fatalf("目录条目异常少: %d", len(catalog))
	}
	for key, e := range catalog {
		if e[EN] == "" || e[ZH] == "" {
			t.Errorf("%s: 缺一种语言的文案", key)
		}
	}
}

func TestDetect(t *testing.T) {
	cases := []struct {
		env  map[string]string
		want Lang
	}{
		{map[string]string{}, EN},
		{map[string]string{"NETHERWAY_LANG": "zh"}, ZH},
		{map[string]string{"NETHERWAY_LANG": "en", "LANG": "zh_CN.UTF-8"}, EN},
		{map[string]string{"LANG": "zh_CN.UTF-8"}, ZH},
		{map[string]string{"LC_ALL": "C.UTF-8", "LANG": "zh_CN.UTF-8"}, EN}, // POSIX：LC_ALL 优先
		{map[string]string{"LC_MESSAGES": "zh_TW.Big5"}, ZH},
		{map[string]string{"LANG": "de_DE.UTF-8"}, EN},
	}
	for i, c := range cases {
		got := detect(func(k string) string { return c.env[k] })
		if got != c.want {
			t.Errorf("case %d: got %v want %v (env=%v)", i, got, c.want, c.env)
		}
	}
}

func TestMissingKeyFallback(t *testing.T) {
	if got := T("no.such.key"); got != "no.such.key" {
		t.Errorf("got %q", got)
	}
	if got := T("no.such.key", 42); !strings.Contains(got, "no.such.key") || !strings.Contains(got, "42") {
		t.Errorf("got %q", got)
	}
	if err := Errorf("no.such.key", 42); !strings.Contains(err.Error(), "no.such.key") {
		t.Errorf("got %v", err)
	}
}
