import React from "react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";

export function Markdown({ children, className }: { children: string; className?: string }) {
  return (
    <div className={className}>
      <ReactMarkdown
        remarkPlugins={[remarkGfm]}
        components={{
          a: ({ href, children }) => (
            <a href={href} target="_blank" rel="noopener noreferrer" className="text-accent underline underline-offset-2 hover:brightness-110">
              {children}
            </a>
          ),
          code: ({ className, children, ...props }) => {
            const match = /language-(\w+)/.exec(className ?? "");
            const isInline = !match;
            if (isInline) {
              return (
                <code className="bg-sunken px-1 py-0.5 rounded text-[90%] font-mono" {...props}>
                  {children}
                </code>
              );
            }
            return (
              <pre className="bg-sunken border border-edge rounded-lg p-3 overflow-x-auto my-2">
                <code className={`text-[12.5px] font-mono leading-relaxed ${className ?? ""}`} {...props}>
                  {children}
                </code>
              </pre>
            );
          },
          pre: ({ children }) => <>{children}</>,
          table: ({ children }) => (
            <div className="overflow-x-auto my-2">
              <table className="w-full border-collapse text-[12.5px]">{children}</table>
            </div>
          ),
          th: ({ children }) => (
            <th className="border border-edge bg-sunken px-2.5 py-1.5 text-left font-semibold">{children}</th>
          ),
          td: ({ children }) => (
            <td className="border border-edge px-2.5 py-1.5">{children}</td>
          ),
          ul: ({ children }) => <ul className="list-disc pl-5 my-1 space-y-0.5">{children}</ul>,
          ol: ({ children }) => <ol className="list-decimal pl-5 my-1 space-y-0.5">{children}</ol>,
          li: ({ children }) => <li className="leading-relaxed">{children}</li>,
          blockquote: ({ children }) => (
            <blockquote className="border-l-3 border-accent/30 pl-3 my-2 text-faint italic">{children}</blockquote>
          ),
          hr: () => <hr className="my-3 border-t border-edge" />,
          h1: ({ children }) => <h1 className="text-base font-bold mt-4 mb-1.5">{children}</h1>,
          h2: ({ children }) => <h2 className="text-[15px] font-bold mt-3.5 mb-1">{children}</h2>,
          h3: ({ children }) => <h3 className="text-[14px] font-bold mt-3 mb-1">{children}</h3>,
          p: ({ children }) => <p className="my-1.5 last:mb-0">{children}</p>,
        }}
      >
        {children}
      </ReactMarkdown>
    </div>
  );
}